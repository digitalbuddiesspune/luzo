package com.craft.ludo.tournament

import com.craft.ludo.gameplay.*
import com.craft.ludo.identity.SessionPrincipal
import com.craft.ludo.shared.api.DomainException
import com.craft.ludo.shared.support.newId
import com.craft.ludo.wallet.WalletReservation
import com.craft.ludo.wallet.WalletService
import org.bson.Document
import org.slf4j.LoggerFactory
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Service
class TournamentService(
    private val store: TournamentStore,
    private val bracket: TournamentBracketService,
    private val matchService: MatchService,
    private val matchRepository: MatchRepository,
    private val roomRepository: RoomRepository,
    private val clock: Clock,
    private val participationGate: GameParticipationGate,
    private val walletService: WalletService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val busy = AtomicBoolean(false)
    private fun now() = Instant.now(clock)
    private fun conflict(message: String): Nothing = throw DomainException(HttpStatus.CONFLICT, message)

    fun validate(config: TournamentConfig) {
        require(config.name.trim().length in 3..100) { "Name must contain 3–100 characters." }
        require(config.description.length <= 4000 && config.rules.length in 20..8000) { "Provide tournament rules (20–8000 characters)." }
        require(config.bannerUrl.isBlank() || (config.bannerUrl.startsWith("https://") && config.bannerUrl.length <= 2000)) { "Banner must be an HTTPS URL." }
        require(config.registrationStart < config.registrationEnd && config.registrationEnd <= config.startAt) { "Registration must close after opening and before tournament start." }
        require(config.joinTimeoutSeconds in 30..1800 && config.matchDurationSeconds in 300..14400 && config.reconnectSeconds in 15..300) { "Invalid match, join, or reconnect timeout." }
        require(config.entryFee >= 0 && config.prizePoints in 0..1_000_000) { "Invalid fee or point reward." }
        require(config.entryType != EntryType.FREE || config.entryFee == 0L) { "Free tournaments cannot charge an entry fee." }
        require(config.entryType != EntryType.PAID || config.entryFee > 0) { "Paid configurations require a positive entry fee." }
        bracket.preview(config.maxParticipants, config.gameFormat, config.qualifiersPerRound)
    }

    private fun event(t: TournamentDocument, actor: String, action: String, reason: String = ""): TournamentDocument = t.copy(
        updatedAt = now(), events = (t.events + TournamentAudit(newId("taudit"), t.id, actor, action, reason, now())).takeLast(24),
        registeredParticipants = t.registrations.count { it.status == "CONFIRMED" },
    )

    fun create(config: TournamentConfig, admin: String): Mono<TournamentDocument> {
        validate(config)
        return store.create(event(TournamentDocument(newId("tournament"), config, admin, createdAt = now(), updatedAt = now()), admin, "CREATED"))
    }

    fun update(id: String, config: TournamentConfig, admin: String) = store.mutate(id) { t ->
        if (t.status != TournamentStatus.DRAFT) conflict("Published tournament rules cannot be changed.")
        validate(config)
        Mono.just(event(t.copy(config = config), admin, "UPDATED"))
    }

    fun publish(id: String, admin: String) = store.mutate(id) { t ->
        if (t.status != TournamentStatus.DRAFT) conflict("Only drafts can be published.")
        if (t.config.registrationEnd <= now()) conflict("Registration closing time must be in the future.")
        Mono.just(event(t.copy(status = if (t.config.registrationStart <= now()) TournamentStatus.REGISTRATION_OPEN else TournamentStatus.UPCOMING), admin, "TOURNAMENT_UPDATED", "Published rules and bracket preview"))
    }

    fun get(id: String) = store.get(id)
    fun list(admin: Boolean = false) = store.all().filter { admin || it.status != TournamentStatus.DRAFT }
    fun mine(userId: String) = store.all().filter { it.registrations.any { r -> r.userId == userId } }

    fun register(id: String, user: SessionPrincipal): Mono<TournamentDocument> = store.get(id).flatMap { initial ->
        val checked = refreshSchedule(initial)
        val existing = checked.registrations.find { it.userId == user.id }
        if (existing?.status == "CONFIRMED") return@flatMap Mono.just(checked)
        if (existing?.status == "DISQUALIFIED") conflict("You have been disqualified from this tournament.")
        if (checked.status != TournamentStatus.REGISTRATION_OPEN || now() < checked.config.registrationStart || now() >= checked.config.registrationEnd) conflict("Registration is closed.")
        if (checked.registrations.count { it.status == "CONFIRMED" } >= checked.config.maxParticipants) conflict("Tournament is full.")

        val reserveMono: Mono<WalletReservation> = if (checked.config.entryType == EntryType.PAID && checked.config.entryFee > 0) {
            walletService.reserveEntryFee(
                userId = user.id,
                roomId = checked.id,
                amount = checked.config.entryFee,
                ipAddress = null,
                startAttemptId = newId("treg"),
                roundId = checked.id,
            )
        } else {
            Mono.empty()
        }

        reserveMono.defaultIfEmpty(WalletReservation("", "", 0)).flatMap { maybeRes ->
            val reservation = maybeRes.takeIf { it.transactionId.isNotEmpty() }
            val alreadyConfirmed = AtomicBoolean(false)
            store.mutate(id) { original ->
                val t = refreshSchedule(original)
                val current = t.registrations.find { it.userId == user.id }
                if (current?.status == "CONFIRMED") {
                    alreadyConfirmed.set(true)
                    return@mutate Mono.just(t)
                }
                if (current?.status == "DISQUALIFIED") conflict("You have been disqualified from this tournament.")
                if (t.status != TournamentStatus.REGISTRATION_OPEN || now() < t.config.registrationStart || now() >= t.config.registrationEnd) conflict("Registration is closed.")
                if (t.registrations.count { it.status == "CONFIRMED" } >= t.config.maxParticipants) conflict("Tournament is full.")
                val reg = TournamentRegistration(
                    id = "${t.id}:${user.id}",
                    tournamentId = t.id,
                    userId = user.id,
                    displayName = user.displayName,
                    registeredAt = now(),
                    paymentStatus = if (reservation != null) "PAID" else "NOT_REQUIRED",
                    entryFee = reservation?.amount ?: 0,
                    walletReservation = reservation,
                )
                val player = TournamentParticipant(reg.id, t.id, user.id, user.displayName)
                Mono.just(event(t.copy(registrations = t.registrations.filterNot { it.userId == user.id } + reg,
                    participants = t.participants.filterNot { it.userId == user.id } + player), user.id, "REGISTERED"))
            }.flatMap { saved ->
                if (alreadyConfirmed.get() && reservation != null) {
                    walletService.refundReservation(reservation, id).thenReturn(saved)
                } else {
                    Mono.just(saved)
                }
            }.onErrorResume { error ->
                if (reservation != null) {
                    walletService.refundReservation(reservation, id)
                        .onErrorResume { refundErr ->
                            log.error("Failed to refund reservation after tournament registration error id={} userId={}", id, user.id, refundErr)
                            Mono.empty()
                        }
                        .then(Mono.error(error))
                } else {
                    Mono.error(error)
                }
            }
        }
    }

    fun cancelRegistration(id: String, user: SessionPrincipal): Mono<TournamentDocument> {
        val refundTarget = AtomicReference<WalletReservation?>(null)
        return store.mutate(id) { t ->
            if (t.rounds.isNotEmpty() || t.status !in listOf(TournamentStatus.UPCOMING, TournamentStatus.REGISTRATION_OPEN, TournamentStatus.REGISTRATION_CLOSED)) conflict("Registration can no longer be cancelled.")
            val existing = t.registrations.find { it.userId == user.id && it.status == "CONFIRMED" }
            if (existing?.paymentStatus == "PAID" && existing.walletReservation != null) {
                refundTarget.set(existing.walletReservation)
            }
            Mono.just(event(t.copy(registrations = t.registrations.map {
                if (it.userId == user.id) it.copy(status = "CANCELLED", paymentStatus = if (it.paymentStatus == "PAID") "REFUNDED" else it.paymentStatus) else it
            }, participants = t.participants.map { if (it.userId == user.id) it.copy(status = "WITHDRAWN") else it }), user.id, "REGISTRATION_CANCELLED"))
        }.flatMap { saved ->
            val res = refundTarget.get()
            if (res != null) walletService.refundReservation(res, id).thenReturn(saved) else Mono.just(saved)
        }
    }

    private fun refreshSchedule(t: TournamentDocument): TournamentDocument {
        val status = when {
            t.status in listOf(TournamentStatus.UPCOMING, TournamentStatus.REGISTRATION_OPEN) && now() >= t.config.registrationEnd -> TournamentStatus.REGISTRATION_CLOSED
            t.status == TournamentStatus.UPCOMING && now() >= t.config.registrationStart -> TournamentStatus.REGISTRATION_OPEN
            else -> t.status
        }
        return if (status == t.status) t else event(t.copy(status = status), "system", "TOURNAMENT_UPDATED")
    }

    fun start(id: String, actor: String) = store.mutate(id) { original ->
        val t = refreshSchedule(original)
        if (t.status == TournamentStatus.LIVE || t.rounds.isNotEmpty()) return@mutate Mono.just(t)
        if (t.status != TournamentStatus.REGISTRATION_CLOSED) conflict("Close registration before starting.")
        val users = t.registrations.filter { it.status == "CONFIRMED" }.map { it.userId }.shuffled(SecureRandom())
        if (users.size < t.config.gameFormat) conflict("Not enough registered players to start.")
        val generated = bracket.generate(t, users)
        Mono.just(activateRound(event(event(generated, actor, "BRACKET_GENERATED"), "system", "TOURNAMENT_STARTING"), 1))
    }

    private fun activateRound(t: TournamentDocument, number: Int): TournamentDocument {
        val nextMatches = t.matches.map { m ->
            if (m.roundNumber != number) m else {
                val ids = m.sources.map { bracket.resolve(it, t.matches) ?: conflict("A verified qualifier is missing.") }
                m.copy(assignedPlayerIds = ids, status = TournamentMatchStatus.READY, joinDeadline = now().plusSeconds(t.config.joinTimeoutSeconds))
            }
        }
        val round = t.rounds.first { it.number == number }
        val byeIds = round.byeSources.mapNotNull { bracket.resolve(it, t.matches) }
        return event(t.copy(status = TournamentStatus.LIVE, currentRound = number, matches = nextMatches,
            rounds = t.rounds.map { if (it.number == number) it.copy(status = "LIVE", qualifiedPlayerIds = byeIds) else it },
            participants = t.participants.map { p ->
                val match = nextMatches.find { it.roundNumber == number && p.userId in it.assignedPlayerIds }
                when {
                    match != null -> p.copy(currentRound = number, currentMatchId = match.id, status = "MATCH_ASSIGNED")
                    p.userId in byeIds -> p.copy(currentRound = number, currentMatchId = null, status = "BYE")
                    else -> p
                }
            }), "system", "MATCH_ASSIGNED", "Round $number")
    }

    fun findMatch(id: String): Mono<Pair<TournamentDocument, TournamentMatch>> = store.mongo.findById(id, TournamentMatch::class.java)
        .switchIfEmpty(Mono.error(DomainException(HttpStatus.NOT_FOUND, "Tournament match not found.")))
        .flatMap { m -> store.get(m.tournamentId).map { t -> t to t.matches.first { it.id == id } } }

    fun joinMatch(id: String, user: SessionPrincipal): Mono<TournamentDocument> = findMatch(id).flatMap { (parent, _) ->
        store.mutate(parent.id) { t ->
            if (t.status != TournamentStatus.LIVE) conflict("Tournament is not live.")
            val m = t.matches.first { it.id == id }
            if (user.id !in m.assignedPlayerIds) throw DomainException(HttpStatus.FORBIDDEN, "You are not assigned to this match.")
            if (user.id in m.noShowPlayerIds) conflict("You forfeited this match by missing check-in.")
            if (m.status == TournamentMatchStatus.IN_PROGRESS) return@mutate Mono.just(t)
            if (m.status != TournamentMatchStatus.READY || now() >= (m.joinDeadline ?: Instant.MIN)) conflict("Match check-in has closed.")
            if (user.id in m.joinedPlayerIds) return@mutate Mono.just(t)
            val activeGameQuery = Query(Criteria.where("status").`is`(MatchStatus.ACTIVE).and("players").elemMatch(Criteria.where("userId").`is`(user.id).and("isAbandoned").ne(true)))
            store.mongo.exists(activeGameQuery, MatchDocument::class.java).flatMap { active ->
                if (active) conflict("Finish your current game before joining this match.")
                val lock = Document("_id", user.id).append("tournamentId", t.id).append("matchId", m.id)
                store.mongo.findById(user.id, Document::class.java, "tournament_player_locks")
                    .flatMap { existing ->
                        if (existing.getString("matchId") != m.id) conflict("You are already checked into another tournament match.")
                        Mono.just(existing)
                    }.switchIfEmpty(store.mongo.insert(lock, "tournament_player_locks"))
                    .then(participationGate.acquire(user.id, "${m.id}:game${m.attempt}"))
                    .thenReturn(event(t.copy(matches = t.matches.map { if (it.id == id) it.copy(joinedPlayerIds = it.joinedPlayerIds + user.id) else it }), user.id, "PLAYER_READY"))
            }
        }
    }

    private fun replaceMatch(t: TournamentDocument, m: TournamentMatch) = t.copy(matches = t.matches.map { if (it.id == m.id) m else it })

    private fun launchMatch(t: TournamentDocument, m: TournamentMatch): Mono<TournamentDocument> {
        val present = m.assignedPlayerIds.filter { it in m.joinedPlayerIds }
        val missing = m.assignedPlayerIds - present.toSet()
        val checked = m.copy(noShowPlayerIds = missing)
        if (present.size <= m.qualifierCount) {
            if (present.isEmpty() || present.size < m.qualifierCount) {
                return Mono.just(event(replaceMatch(t, checked.copy(status = TournamentMatchStatus.DISPUTED)).copy(status = TournamentStatus.UNDER_REVIEW), "system", "MATCH_DISPUTED", "Too few checked-in players to qualify fairly"))
            }
            return Mono.just(event(replaceMatch(t, checked.copy(status = TournamentMatchStatus.NO_SHOW, winnerId = present.firstOrNull(),
                finishingOrder = present, qualifiedPlayerIds = present, resultVerified = true, completionTime = now())), "system", "MATCH_COMPLETED", "Published no-show forfeit policy"))
        }
        val gameId = "${m.id}:game${m.attempt}"
        val colors = if (m.gameFormat == 2) listOf("red", "yellow") else listOf("red", "green", "yellow", "blue")
        val room = RoomDocument(id = "${gameId}:room", code = newId("code").takeLast(6).uppercase(), mode = RoomMode.TOURNAMENT,
            status = RoomStatus.STARTING, maxPlayers = m.gameFormat, entryFee = 0, createdAt = now(), updatedAt = now(),
            seats = present.mapIndexed { index, userId -> RoomSeat(userId, t.participants.first { it.userId == userId }.displayName, colors[index], false, joinedAt = now()) })
        return roomRepository.save(room).flatMap { matchService.createStartedMatch(it, gameId) }.flatMap { game ->
            // This separate rule configuration never changes Classic/Custom matches.
            matchRepository.save(game.copy(tournamentId = t.id, turnTimeoutSeconds = t.config.reconnectSeconds,
                turnDeadlineAt = now().plusSeconds(t.config.reconnectSeconds + 4)))
        }.map {
            event(replaceMatch(t, checked.copy(status = TournamentMatchStatus.IN_PROGRESS, gameSessionId = gameId, startTime = now())), "system", "MATCH_STARTING", m.id)
        }
    }

    private fun collectResult(t: TournamentDocument, m: TournamentMatch): Mono<TournamentDocument> {
        val gameId = m.gameSessionId ?: return Mono.just(t)
        return matchRepository.findById(gameId).flatMap { game ->
            if (game.status != MatchStatus.FINISHED) {
                if (m.startTime != null && now() >= m.startTime.plusSeconds(t.config.matchDurationSeconds)) {
                    return@flatMap stopGame(game).thenReturn(event(replaceMatch(t, m.copy(status = TournamentMatchStatus.DISPUTED)).copy(status = TournamentStatus.UNDER_REVIEW), "system", "MATCH_DISPUTED", "Match duration exceeded; no guessed winner"))
                }
                return@flatMap Mono.just(t)
            }
            val order = game.tournamentFinishingOrder
            val valid = game.mode == RoomMode.TOURNAMENT && game.tournamentId == t.id && order.isNotEmpty() && order.distinct() == order && order.all { it in m.assignedPlayerIds } && order.size >= m.qualifierCount && game.winnerUserId == order.first()
            if (!valid) return@flatMap Mono.just(event(replaceMatch(t, m.copy(status = TournamentMatchStatus.DISPUTED)).copy(status = TournamentStatus.UNDER_REVIEW), "system", "MATCH_DISPUTED", "Authoritative result could not supply the required qualifiers"))
            val verified = m.copy(status = TournamentMatchStatus.COMPLETED, winnerId = order.first(), finishingOrder = order,
                qualifiedPlayerIds = order.take(m.qualifierCount), resultVerified = true, resultSequence = game.sequence, completionTime = now())
            releaseLocks(m).thenReturn(event(replaceMatch(t, verified), "system", "MATCH_COMPLETED", m.id))
        }.switchIfEmpty(Mono.just(event(t.copy(status = TournamentStatus.UNDER_REVIEW), "system", "MATCH_DISPUTED", "Game session is missing")))
    }

    private fun releaseLocks(m: TournamentMatch): Mono<Void> = store.mongo.remove(Query(Criteria.where("matchId").`is`(m.id)), "tournament_player_locks").then(participationGate.release("${m.id}:game${m.attempt}"))
    private fun stopGame(game: MatchDocument): Mono<Void> = if (game.status == MatchStatus.FINISHED) Mono.empty() else
        matchRepository.save(game.copy(status = MatchStatus.FINISHED, phase = MatchPhase.FINISHED, turnDeadlineAt = null, phaseDeadlineAt = null,
            winnerUserId = null, winnerDisplayName = null, updatedAt = now(), sequence = game.sequence + 1)).then()

    private fun progress(t: TournamentDocument): TournamentDocument {
        if (t.status != TournamentStatus.LIVE) return t
        val roundMatches = t.matches.filter { it.roundNumber == t.currentRound }
        if (roundMatches.any { !it.resultVerified }) return t
        val round = t.rounds.first { it.number == t.currentRound }
        val qualified = roundMatches.flatMap { it.qualifiedPlayerIds } + round.byeSources.mapNotNull { bracket.resolve(it, t.matches) }
        require(qualified.distinct().size == qualified.size) { "Duplicate round qualification." }
        val participants = t.participants.map { p ->
            if (p.currentRound != t.currentRound || p.status in listOf("ELIMINATED", "DISQUALIFIED")) p
            else if (p.userId in qualified) p.copy(status = "QUALIFIED", currentMatchId = null)
            else {
                val m = roundMatches.firstOrNull { p.userId in it.assignedPlayerIds }
                p.copy(status = "ELIMINATED", currentMatchId = null, finalRank = qualified.size + 1,
                    eliminationReason = if (m != null && p.userId in m.noShowPlayerIds) "NO_SHOW" else "MATCH_RESULT")
            }
        }
        val completed = event(t.copy(participants = participants, rounds = t.rounds.map { if (it.number == t.currentRound) it.copy(status = "COMPLETED", qualifiedPlayerIds = qualified) else it }), "system", "PLAYER_QUALIFIED", "Round ${t.currentRound} verified; other players eliminated")
        if (t.currentRound < t.rounds.size) return activateRound(event(completed, "system", "PLAYER_ELIMINATED", "Non-qualifiers eliminated in round ${t.currentRound}"), t.currentRound + 1)
        val champion = qualified.single()
        val finalOrder = roundMatches.single().finishingOrder
        val potAmount = t.registrations.filter { it.status == "CONFIRMED" && it.paymentStatus == "PAID" }.sumOf { it.entryFee }
        val rewards = buildList {
            if (t.config.entryType == EntryType.PAID && potAmount > 0) {
                add(TournamentReward("${t.id}:$champion:CASH", t.id, champion, rewardType = "CASH", amount = potAmount, settledAt = now()))
            }
            if (t.config.prizePoints > 0) {
                add(TournamentReward("${t.id}:$champion:POINTS", t.id, champion, rewardType = "POINTS", amount = t.config.prizePoints, settledAt = now()))
            }
        }
        return event(completed.copy(status = TournamentStatus.COMPLETED, championId = champion, rewards = rewards,
            participants = completed.participants.map { p ->
                val rank = finalOrder.indexOf(p.userId).takeIf { it >= 0 }?.plus(1)
                if (p.userId == champion) p.copy(status = "CHAMPION", finalRank = 1)
                else if (rank != null) p.copy(finalRank = rank) else p
            }), "system", "TOURNAMENT_COMPLETED")
    }

    private fun refundPaidReservations(id: String, reservations: List<WalletReservation>): Mono<Void> =
        Flux.fromIterable(reservations).concatMap { res ->
            walletService.refundReservation(res, id).onErrorResume { err ->
                log.error("Tournament refund failed id={} userId={}", id, res.userId, err)
                Mono.empty()
            }
        }.then()

    private fun settlePaidChampionIfNeeded(beforeStatus: TournamentStatus?, after: TournamentDocument): Mono<TournamentDocument> {
        val champion = after.championId
        if (beforeStatus == TournamentStatus.COMPLETED || after.status != TournamentStatus.COMPLETED || after.config.entryType != EntryType.PAID || champion == null) {
            return Mono.just(after)
        }
        val reservations = after.registrations
            .filter { it.status == "CONFIRMED" && it.paymentStatus == "PAID" && it.walletReservation != null }
            .mapNotNull { it.walletReservation }
        if (reservations.isEmpty()) return Mono.just(after)
        return walletService.payoutWinner(after.id, champion, reservations, after.id)
            .onErrorResume { err ->
                log.error("Paid tournament champion payout failed id={} champion={}", after.id, champion, err)
                Mono.empty()
            }
            .thenReturn(after)
    }

    private fun process(id: String): Mono<TournamentDocument> {
        val beforeStatus = AtomicReference<TournamentStatus?>(null)
        val autoCancelRefunds = AtomicReference<List<WalletReservation>>(emptyList())
        return store.mutate(id) { original ->
            beforeStatus.set(original.status)
            autoCancelRefunds.set(emptyList())
            var t = refreshSchedule(original)
            if (t.status == TournamentStatus.REGISTRATION_CLOSED && now() >= t.config.startAt) {
                val ids = t.registrations.filter { it.status == "CONFIRMED" }.map { it.userId }.shuffled(SecureRandom())
                if (ids.size < t.config.gameFormat) {
                    val toRefund = t.registrations.filter { it.status == "CONFIRMED" && it.paymentStatus == "PAID" && it.walletReservation != null }
                    autoCancelRefunds.set(toRefund.mapNotNull { it.walletReservation })
                    return@mutate Mono.just(event(t.copy(
                        status = TournamentStatus.CANCELLED,
                        registrations = t.registrations.map { if (it.paymentStatus == "PAID") it.copy(paymentStatus = "REFUNDED") else it },
                    ), "system", "TOURNAMENT_CANCELLED", "Not enough participants"))
                }
                t = try { activateRound(event(event(bracket.generate(t, ids), "system", "BRACKET_GENERATED"), "system", "TOURNAMENT_STARTING"), 1) }
                catch (error: IllegalArgumentException) { return@mutate Mono.just(event(t.copy(status = TournamentStatus.UNDER_REVIEW), "system", "TOURNAMENT_UPDATED", error.message ?: "Invalid bracket")) }
            }
            if (t.status != TournamentStatus.LIVE) return@mutate Mono.just(t)
            Flux.fromIterable(t.matches.filter { it.roundNumber == t.currentRound }).reduce(Mono.just(t)) { state, m ->
                state.flatMap { current ->
                    if (current.status != TournamentStatus.LIVE) Mono.just(current)
                    else when {
                        m.status == TournamentMatchStatus.READY && (m.joinedPlayerIds.size == m.assignedPlayerIds.size || now() >= (m.joinDeadline ?: Instant.MIN)) -> launchMatch(current, m)
                        m.status == TournamentMatchStatus.IN_PROGRESS -> collectResult(current, m)
                        else -> Mono.just(current)
                    }
                }
            }.flatMap { it }.flatMap { current ->
                Flux.fromIterable(current.matches.filter { it.resultVerified && it.roundNumber == current.currentRound }).concatMap { releaseLocks(it) }.then().thenReturn(progress(current))
            }
        }.flatMap { after ->
            val refunds = autoCancelRefunds.get()
            val afterRefunds = if (refunds.isNotEmpty()) refundPaidReservations(id, refunds).thenReturn(after) else Mono.just(after)
            afterRefunds.flatMap { settlePaidChampionIfNeeded(beforeStatus.get(), it) }
        }
    }

    fun delete(id: String, admin: String): Mono<Map<String, Any>> = store.get(id).flatMap { t ->
        val refunds = if (t.status != TournamentStatus.COMPLETED) {
            t.registrations.filter { it.status == "CONFIRMED" && it.paymentStatus == "PAID" && it.walletReservation != null }.mapNotNull { it.walletReservation }
        } else emptyList()
        Flux.fromIterable(t.matches).concatMap { m ->
            val stop = m.gameSessionId?.let { matchRepository.findById(it).flatMap(::stopGame) } ?: Mono.empty()
            stop.then(releaseLocks(m))
        }.then(refundPaidReservations(id, refunds)).then(store.delete(id)).doOnSuccess {
            log.info("Tournament deleted id={} admin={}", id, admin)
        }.thenReturn(mapOf("deleted" to true, "id" to id))
    }

    fun cancel(id: String, admin: String, reason: String): Mono<TournamentDocument> {
        val refundTargets = AtomicReference<List<WalletReservation>>(emptyList())
        return store.mutate(id) { t ->
            requireReason(reason)
            if (t.status == TournamentStatus.COMPLETED) conflict("Completed tournaments cannot be cancelled.")
            refundTargets.set(t.registrations.filter { it.status == "CONFIRMED" && it.paymentStatus == "PAID" && it.walletReservation != null }.mapNotNull { it.walletReservation })
            Flux.fromIterable(t.matches).concatMap { m ->
                val stop = m.gameSessionId?.let { matchRepository.findById(it).flatMap(::stopGame) } ?: Mono.empty()
                stop.then(releaseLocks(m))
            }.then().thenReturn(event(t.copy(status = TournamentStatus.CANCELLED,
                registrations = t.registrations.map { if (it.paymentStatus == "PAID") it.copy(paymentStatus = "REFUNDED") else it },
                matches = t.matches.map { if (!it.resultVerified) it.copy(status = TournamentMatchStatus.CANCELLED) else it },
                participants = t.participants.map { it.copy(status = "CANCELLED", currentMatchId = null) }), admin, "TOURNAMENT_CANCELLED", reason))
        }.flatMap { saved ->
            val refunds = refundTargets.get()
            if (refunds.isNotEmpty()) refundPaidReservations(id, refunds).thenReturn(saved) else Mono.just(saved)
        }
    }

    fun intervene(id: String, admin: String, action: String, request: TournamentAdminAction): Mono<TournamentDocument> {
        val beforeStatus = AtomicReference<TournamentStatus?>(null)
        return store.mutate(id) { t ->
            beforeStatus.set(t.status)
            requireReason(request.reason)
            if (t.status in listOf(TournamentStatus.COMPLETED, TournamentStatus.CANCELLED)) conflict("Tournament is already terminal.")
            when (action) {
                "disqualify" -> {
                    val userId = request.userId ?: throw IllegalArgumentException("userId is required.")
                    if (t.participants.none { it.userId == userId }) conflict("Participant not found.")
                    if (t.rounds.isNotEmpty()) {
                        val active = t.matches.find { userId in it.assignedPlayerIds && !it.resultVerified }
                        val next = if (active != null) replaceMatch(t, active.copy(status = TournamentMatchStatus.DISPUTED)) else t
                        val stop = active?.gameSessionId?.let { matchRepository.findById(it).flatMap(::stopGame) } ?: Mono.empty()
                        stop.thenReturn(event(next.copy(status = TournamentStatus.UNDER_REVIEW, participants = next.participants.map { if (it.userId == userId) it.copy(status = "DISQUALIFIED", eliminationReason = request.reason) else it }), admin, "PLAYER_DISQUALIFIED", request.reason))
                    } else Mono.just(event(t.copy(registrations = t.registrations.map { if (it.userId == userId) it.copy(status = "DISQUALIFIED") else it }, participants = t.participants.map { if (it.userId == userId) it.copy(status = "DISQUALIFIED", eliminationReason = request.reason) else it }), admin, "PLAYER_DISQUALIFIED", request.reason))
                }
                "restart-match" -> {
                    val m = t.matches.find { it.id == request.matchId } ?: conflict("Match not found.")
                    if (m.status != TournamentMatchStatus.DISPUTED || m.resultVerified) conflict("Only an unresolved disputed match can be restarted.")
                    val stop = m.gameSessionId?.let { matchRepository.findById(it).flatMap(::stopGame) } ?: Mono.empty()
                    val eligible = m.assignedPlayerIds.filter { id -> t.participants.none { it.userId == id && it.status == "DISQUALIFIED" } }
                    stop.then(releaseLocks(m)).thenReturn(event(replaceMatch(t, m.copy(status = TournamentMatchStatus.READY, assignedPlayerIds = eligible,
                        joinedPlayerIds = emptyList(), noShowPlayerIds = m.assignedPlayerIds - eligible.toSet(), gameSessionId = null, startTime = null,
                        attempt = m.attempt + 1, joinDeadline = now().plusSeconds(t.config.joinTimeoutSeconds))).copy(status = TournamentStatus.LIVE), admin, "MATCH_RESTARTED", request.reason))
                }
                "resolve-match" -> {
                    val m = t.matches.find { it.id == request.matchId } ?: conflict("Match not found.")
                    if (m.status != TournamentMatchStatus.DISPUTED) conflict("Match is not disputed.")
                    collectResult(t.copy(status = TournamentStatus.LIVE), m).map { result -> event(progress(result), admin, "DISPUTE_REVIEWED", request.reason) }
                }
                "force-complete" -> {
                    if (t.matches.none { it.roundNumber == t.rounds.size } || t.matches.filter { it.roundNumber == t.rounds.size }.any { !it.resultVerified }) conflict("The final must have an authoritative verified result. Administrators cannot invent a winner.")
                    Mono.just(event(progress(t.copy(status = TournamentStatus.LIVE)), admin, "FORCE_COMPLETE_REVIEWED", request.reason))
                }
                else -> throw IllegalArgumentException("Unknown tournament action.")
            }
        }.flatMap { after -> settlePaidChampionIfNeeded(beforeStatus.get(), after) }
    }

    private fun requireReason(reason: String) { require(reason.trim().length in 5..1000) { "A reason of 5–1000 characters is required." } }

    @Scheduled(fixedDelay = 2000)
    fun tick() {
        if (!busy.compareAndSet(false, true)) return
        store.all().filter { it.status in listOf(TournamentStatus.UPCOMING, TournamentStatus.REGISTRATION_OPEN, TournamentStatus.REGISTRATION_CLOSED, TournamentStatus.LIVE) }
            .concatMap { t -> process(t.id).onErrorResume { error -> log.error("Tournament progression failed id={} type={}", t.id, error.javaClass.simpleName, error); Mono.empty() } }
            .doFinally { busy.set(false) }.subscribe({}, { error -> log.error("Tournament scheduler failed", error) })
    }
}
