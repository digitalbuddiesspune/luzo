package com.craft.ludo.gameplay

import com.craft.ludo.identity.SessionPrincipal
import com.craft.ludo.shared.api.DomainException
import com.craft.ludo.shared.config.AppProperties
import com.craft.ludo.shared.support.newId
import com.craft.ludo.wallet.WalletReservation
import com.craft.ludo.wallet.WalletService
import org.slf4j.LoggerFactory
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Production public online matchmaking.
 *
 * Invariants:
 * - Bots never persist in WAITING rooms.
 * - Failed starts mark the room FINISHED (never WAITING + bots).
 * - Fees are reserved only after an atomic WAITING -> STARTING claim.
 */
@Service
class OnlineMatchmakingService(
    private val roomRepository: RoomRepository,
    private val matchRepository: MatchRepository,
    private val matchService: MatchService,
    private val walletService: WalletService,
    private val instanceCoordinator: AppInstanceCoordinator,
    private val mongoTemplate: ReactiveMongoTemplate,
    private val clock: Clock,
    appProperties: AppProperties,
) {
    private val log = LoggerFactory.getLogger(OnlineMatchmakingService::class.java)
    private val onlineEntryFee = appProperties.gameplay.onlineEntryFee
    private val lobbyWaitMillis = appProperties.gameplay.lobbyWaitMillis
    private val onlinePvpRealPlayerThreshold = appProperties.gameplay.onlinePvpRealPlayerThreshold

    init {
        require(onlineEntryFee > 0) { "app.gameplay.online-entry-fee must be positive." }
        require(lobbyWaitMillis > 0) { "app.gameplay.lobby-wait-millis must be positive." }
        require(onlinePvpRealPlayerThreshold >= 0) {
            "app.gameplay.online-pvp-real-player-threshold cannot be negative."
        }
    }

    fun joinOnlineMatch(principal: SessionPrincipal, requestedMaxPlayers: Int?): Mono<JoinOnlineMatchResponse> {
        val maxPlayers = normalizeOnlineMaxPlayers(requestedMaxPlayers)
        log.info(
            "Ludo online lobby join requested userId={} displayName={} requestedMaxPlayers={} resolvedMaxPlayers={}",
            principal.id,
            principal.displayName,
            requestedMaxPlayers,
            maxPlayers,
        )
        return instanceCoordinator.withLock("online-join", principal.id) {
            joinUnlocked(principal, maxPlayers)
        }
            .switchIfEmpty(
                Mono.delay(Duration.ofMillis(250))
                    .then(joinUnlocked(principal, maxPlayers)),
            )
    }

    fun processDueWaitingRooms(): Mono<Void> {
        val now = Instant.now(clock)
        val currentInstanceId = instanceCoordinator.instanceId

        val dueWaiting = roomRepository.findAllByStatusOrderByCreatedAtAsc(RoomStatus.WAITING)
            .filter { room ->
                room.mode == RoomMode.ONLINE_PUBLIC &&
                    room.matchId == null &&
                    room.seats.none { it.isBot } &&
                    canProcessOnlineWaitingRoom(room, now, currentInstanceId)
            }

        val hungStarting = roomRepository.findAllByStatusOrderByCreatedAtAsc(RoomStatus.STARTING)
            .filter { room ->
                room.mode == RoomMode.ONLINE_PUBLIC &&
                    room.matchId == null &&
                    isOnlineRoomStartingStale(room, now)
            }

        return dueWaiting
            .concatMap { room ->
                instanceCoordinator.withLock("room-tick", room.id) {
                    startPublicRoom(room.id)
                        .doOnError { error -> logStartFailure(room, error) }
                        .onErrorResume { Mono.empty() }
                        .then()
                }
            }
            .thenMany(
                hungStarting.concatMap { room ->
                    instanceCoordinator.withLock("room-tick", room.id) {
                        recoverHungStartingRoom(room)
                    }
                },
            )
            .then()
    }

    fun leaveOnlineRoom(userId: String): Mono<Void> {
        return findExistingPublicRoomForUser(userId)
            .switchIfEmpty(
                Mono.error(DomainException(HttpStatus.NOT_FOUND, "Online room not found for user.")),
            )
            .flatMap { room ->
                when {
                    room.status == RoomStatus.WAITING && room.matchId == null -> leaveLobby(room, userId)
                    room.status == RoomStatus.STARTING && room.matchId == null -> leaveLobby(room, userId)
                    room.status == RoomStatus.ACTIVE && room.matchId != null ->
                        matchService.leaveActiveMatch(room, userId)
                    else -> Mono.error(
                        DomainException(HttpStatus.CONFLICT, "Online room can no longer be left."),
                    )
                }
            }
    }

    private fun joinUnlocked(principal: SessionPrincipal, maxPlayers: Int): Mono<JoinOnlineMatchResponse> {
        return findExistingPublicRoomForUser(principal.id)
            .flatMap { room -> handleExistingRoom(room, principal, maxPlayers) }
            .switchIfEmpty(
                findJoinablePublicRoom(principal, maxPlayers)
                    .flatMap { room -> joinExistingWaitingRoom(room, principal, maxPlayers) }
                    .switchIfEmpty(createWaitingRoom(principal, maxPlayers)),
            )
    }

    private fun handleExistingRoom(
        room: RoomDocument,
        principal: SessionPrincipal,
        maxPlayers: Int,
    ): Mono<JoinOnlineMatchResponse> {
        val now = Instant.now(clock)

        if (room.status == RoomStatus.ACTIVE && room.matchId != null) {
            return respondForActiveRoom(room, principal, maxPlayers)
        }

        if (isStuckOnlineLobbyRoom(room, now) || isCorruptOnlineWaitingRoom(room)) {
            log.warn(
                "Abandoning stuck online lobby userId={} roomId={} roomCode={} status={} ownerInstanceId={}",
                principal.id,
                room.id,
                room.code,
                room.status,
                room.ownerInstanceId,
            )
            return finishLobbyRoom(room).then(createWaitingRoom(principal, maxPlayers))
        }

        if (room.status == RoomStatus.STARTING && room.matchId == null) {
            return Mono.just(JoinOnlineMatchResponse(room = room.toSummary()))
        }

        if (room.status == RoomStatus.WAITING && room.matchId == null) {
            val hydrated = hydrateDeadlineIfNeeded(room, now)
            return hydrated.flatMap { waitingRoom ->
                if (shouldStartOnlineWaitingRoom(waitingRoom, Instant.now(clock))) {
                    startPublicRoom(waitingRoom.id)
                        .flatMap { started -> toJoinResponse(started, principal, maxPlayers) }
                        .onErrorResume { error ->
                            logStartFailure(waitingRoom, error)
                            createWaitingRoom(principal, maxPlayers)
                        }
                } else {
                    Mono.just(JoinOnlineMatchResponse(room = waitingRoom.toSummary()))
                }
            }
        }

        return finishLobbyRoom(room).then(createWaitingRoom(principal, maxPlayers))
    }

    private fun hydrateDeadlineIfNeeded(room: RoomDocument, now: Instant): Mono<RoomDocument> {
        if (room.effectiveWaitingDeadlineAt() != null) {
            return Mono.just(room)
        }
        // Missing deadline means the room should start ASAP (e.g. rolled back from STARTING).
        return roomRepository.save(
            room.copy(
                ownedWaitingDeadlineAt = now,
                waitingDeadlineAt = null,
                ownerInstanceId = room.ownerInstanceId ?: instanceCoordinator.instanceId,
                updatedAt = now,
            ),
        )
    }

    private fun joinExistingWaitingRoom(
        room: RoomDocument,
        principal: SessionPrincipal,
        maxPlayers: Int,
    ): Mono<JoinOnlineMatchResponse> {
        rejectSameSourceRealPlayer(room.seats, principal, room.id, room.code)

        val now = Instant.now(clock)
        val updatedSeats = normalizeSeatColors(
            room.seats + RoomSeat(
                userId = principal.id,
                displayName = principal.displayName,
                color = "",
                isBot = false,
                joinedAt = now,
                ipAddress = principal.ipAddress,
                operatorUserId = principal.operatorUserId,
                operatorId = principal.operatorId,
            ),
        )

        return roomRepository.save(
            room.copy(
                seats = updatedSeats,
                updatedAt = now,
            ),
        )
            .doOnNext { saved ->
                log.info(
                    "Ludo online lobby joined roomId={} roomCode={} userId={} realSeats={} maxPlayers={}",
                    saved.id,
                    saved.code,
                    principal.id,
                    countRealSeats(saved),
                    saved.maxPlayers,
                )
            }
            .flatMap { saved ->
                if (isOnlineWaitingRoomFull(saved) || shouldStartOnlineWaitingRoom(saved, Instant.now(clock))) {
                    startPublicRoom(saved.id)
                        .flatMap { started -> toJoinResponse(started, principal, maxPlayers) }
                        .onErrorResume { error ->
                            logStartFailure(saved, error)
                            Mono.just(JoinOnlineMatchResponse(room = saved.toSummary()))
                        }
                } else {
                    Mono.just(JoinOnlineMatchResponse(room = saved.toSummary()))
                }
            }
    }

    private fun createWaitingRoom(
        principal: SessionPrincipal,
        maxPlayers: Int,
    ): Mono<JoinOnlineMatchResponse> {
        val now = Instant.now(clock)
        val room = RoomDocument(
            mode = RoomMode.ONLINE_PUBLIC,
            status = RoomStatus.WAITING,
            maxPlayers = maxPlayers,
            entryFee = onlineEntryFee,
            ownerInstanceId = instanceCoordinator.instanceId,
            createdAt = now,
            updatedAt = now,
            waitingDeadlineAt = null,
            ownedWaitingDeadlineAt = now.plusMillis(lobbyWaitMillis),
            seats = normalizeSeatColors(
                listOf(
                    RoomSeat(
                        userId = principal.id,
                        displayName = principal.displayName,
                        color = "",
                        isBot = false,
                        joinedAt = now,
                        ipAddress = principal.ipAddress,
                        operatorUserId = principal.operatorUserId,
                        operatorId = principal.operatorId,
                    ),
                ),
            ),
        )

        return roomRepository.save(room)
            .doOnNext { saved ->
                log.info(
                    "Ludo online lobby created roomId={} roomCode={} userId={} maxPlayers={} entryFee={} waitingDeadlineAt={}",
                    saved.id,
                    saved.code,
                    principal.id,
                    saved.maxPlayers,
                    saved.entryFee,
                    saved.effectiveWaitingDeadlineAt(),
                )
            }
            .map { saved -> JoinOnlineMatchResponse(room = saved.toSummary()) }
    }

    /**
     * Atomic WAITING -> STARTING claim with bots, then fees, then match.
     * Failures finish the room (never return WAITING with bots).
     */
    fun startPublicRoom(roomId: String): Mono<RoomDocument> {
        return roomRepository.findById(roomId)
            .flatMap { room ->
                val now = Instant.now(clock)
                when {
                    room.matchId != null || room.status == RoomStatus.ACTIVE -> Mono.just(room)
                    room.status == RoomStatus.STARTING -> {
                        if (isOnlineRoomStartingStale(room, now)) {
                            finishLobbyRoom(room).then(Mono.empty())
                        } else {
                            Mono.just(room)
                        }
                    }
                    room.status != RoomStatus.WAITING -> Mono.just(room)
                    room.seats.any { it.isBot } -> {
                        // Corrupt legacy state — finish instead of healing into WAITING+bots cycles.
                        finishLobbyRoom(room).then(Mono.empty())
                    }
                    else -> claimAndStart(room, now)
                }
            }
    }

    private fun claimAndStart(room: RoomDocument, now: Instant): Mono<RoomDocument> {
        val humans = randomizeSeatColors(room.seats.filter { !it.isBot && !it.isAbandoned })
        if (humans.isEmpty()) {
            return roomRepository.delete(room).then(Mono.empty())
        }

        val seatsToStart = padWithBots(humans, room.maxPlayers, now)
        val startAttemptId = newId("roomstart")
        val prepared = room.copy(
            seats = seatsToStart,
            status = RoomStatus.STARTING,
            startAttemptId = startAttemptId,
            ownerInstanceId = instanceCoordinator.instanceId,
            updatedAt = now,
            waitingDeadlineAt = null,
            ownedWaitingDeadlineAt = null,
            walletReservations = emptyList(),
        )

        log.info(
            "Ludo online room start requested roomId={} roomCode={} realSeats={} maxPlayers={} entryFee={}",
            room.id,
            room.code,
            humans.size,
            room.maxPlayers,
            room.entryFee,
        )

        return claimWaitingToStarting(prepared)
            .flatMap { claimed ->
                if (claimed.startAttemptId != startAttemptId) {
                    val claimNow = Instant.now(clock)
                    return@flatMap when {
                        claimed.status == RoomStatus.ACTIVE && claimed.matchId != null ->
                            Mono.just(claimed)
                        claimed.status == RoomStatus.STARTING &&
                            claimed.startAttemptId != null &&
                            !isOnlineRoomStartingStale(claimed, claimNow) -> {
                            log.info(
                                "Ludo online room start claim held by peer roomId={} roomCode={} startAttemptId={}",
                                claimed.id,
                                claimed.code,
                                claimed.startAttemptId,
                            )
                            Mono.just(claimed)
                        }
                        claimed.status == RoomStatus.STARTING &&
                            isOnlineRoomStartingStale(claimed, claimNow) -> {
                            finishLobbyRoom(claimed).then(Mono.empty())
                        }
                        claimed.status == RoomStatus.WAITING && claimed.matchId == null &&
                            claimed.seats.none { it.isBot } -> {
                            // Lost the race back to WAITING — retry once.
                            claimAndStart(claimed, Instant.now(clock))
                        }
                        else -> Mono.just(claimed)
                    }
                }

                continueAfterClaim(claimed, humans)
            }
    }

    private fun continueAfterClaim(
        claimed: RoomDocument,
        humans: List<RoomSeat>,
    ): Mono<RoomDocument> {
        log.info(
            "Ludo online room start claimed roomId={} roomCode={} startAttemptId={} seats={} botSeats={}",
            claimed.id,
            claimed.code,
            claimed.startAttemptId,
            claimed.seats.size,
            claimed.seats.count { it.isBot },
        )

        return reserveEntryFeesForSeats(humans, claimed.id, claimed.entryFee, claimed.startAttemptId)
            .onErrorResume { error ->
                logStartFailure(claimed, error)
                finishLobbyRoom(claimed).then(Mono.error(error))
            }
            .flatMap { humanReservations ->
                val botReservations = syntheticBotFees(
                    claimed.seats.filter { it.isBot },
                    claimed.entryFee,
                )
                matchService.createStartedMatch(
                    claimed.copy(
                        walletReservations = humanReservations + botReservations,
                    ),
                )
                    .then(roomRepository.findById(claimed.id))
                    .onErrorResume { error ->
                        refundReservations(humanReservations, claimed.id)
                            .then(finishLobbyRoom(claimed))
                            .then(Mono.error(error))
                    }
            }
    }

    private fun claimWaitingToStarting(prepared: RoomDocument): Mono<RoomDocument> {
        val criteria = Criteria.where("id").`is`(prepared.id)
            .and("status").`is`(RoomStatus.WAITING)
            .and("matchId").`is`(null)
        val update = Update()
            .set("status", RoomStatus.STARTING)
            .set("startAttemptId", prepared.startAttemptId)
            .set("ownerInstanceId", prepared.ownerInstanceId)
            .set("seats", prepared.seats)
            .set("walletReservations", emptyList<WalletReservation>())
            .set("updatedAt", prepared.updatedAt)
            .set("waitingDeadlineAt", null)
            .set("ownedWaitingDeadlineAt", null)

        return mongoTemplate.findAndModify(
            Query.query(criteria),
            update,
            FindAndModifyOptions.options().returnNew(true),
            RoomDocument::class.java,
        )
            .switchIfEmpty(roomRepository.findById(prepared.id))
    }

    private fun padWithBots(humans: List<RoomSeat>, maxPlayers: Int, now: Instant): List<RoomSeat> {
        val seats = humans.toMutableList()
        val remainingColors = if (maxPlayers == 2 && humans.size == 1) {
            listOf(diagonalOppositeColor(humans.first().color))
        } else {
            playerColors
                .filterNot { color -> humans.any { seat -> seat.color == color } }
                .shuffled()
        }
        while (seats.size < maxPlayers) {
            val color = remainingColors[seats.size - humans.size]
            seats.add(
                RoomSeat(
                    userId = newId("bot"),
                    displayName = botDisplayName(color),
                    color = color,
                    isBot = true,
                    joinedAt = now,
                ),
            )
        }
        return seats
    }

    private fun toJoinResponse(
        room: RoomDocument,
        principal: SessionPrincipal,
        maxPlayers: Int,
    ): Mono<JoinOnlineMatchResponse> {
        if (room.status == RoomStatus.ACTIVE && room.matchId != null) {
            return respondForActiveRoom(room, principal, maxPlayers)
        }
        if (room.status == RoomStatus.FINISHED || room.matchId == null && room.status != RoomStatus.STARTING &&
            room.status != RoomStatus.WAITING
        ) {
            return createWaitingRoom(principal, maxPlayers)
        }
        return Mono.just(JoinOnlineMatchResponse(room = room.toSummary()))
    }

    private fun respondForActiveRoom(
        room: RoomDocument,
        principal: SessionPrincipal,
        maxPlayers: Int,
    ): Mono<JoinOnlineMatchResponse> {
        val matchId = room.matchId ?: return finishLobbyRoom(room).then(createWaitingRoom(principal, maxPlayers))

        return matchService.processDueMatches()
            .then(matchRepository.findById(matchId))
            .flatMap { match ->
                val userStillActive = match.players.any { player ->
                    player.userId == principal.id && !player.isBot && !player.isEffectivelyAbandoned()
                }

                when {
                    match.status == MatchStatus.FINISHED || match.phase == MatchPhase.FINISHED -> {
                        settleAndMarkRoomFinished(room, match)
                            .then(createWaitingRoom(principal, maxPlayers))
                    }
                    !userStillActive -> {
                        releaseAbandonedUserFromActiveRoom(room, match, principal.id)
                            .then(createWaitingRoom(principal, maxPlayers))
                    }
                    else -> Mono.just(
                        JoinOnlineMatchResponse(
                            room = room.toSummary(),
                            match = match.toSnapshot(),
                            websocketPath = "/ws/matches/${match.id}?sessionToken=${principal.sessionToken}",
                        ),
                    )
                }
            }
            .switchIfEmpty(
                Mono.defer {
                    log.warn(
                        "Active online room missing match doc; finishing roomId={} roomCode={} matchId={}",
                        room.id,
                        room.code,
                        matchId,
                    )
                    finishLobbyRoom(room).then(createWaitingRoom(principal, maxPlayers))
                },
            )
    }

    private fun findExistingPublicRoomForUser(userId: String): Mono<RoomDocument> {
        val query = Query.query(
            Criteria.where("mode").`is`(RoomMode.ONLINE_PUBLIC)
                .and("status").`in`(RoomStatus.WAITING, RoomStatus.STARTING, RoomStatus.ACTIVE)
                .and("seats").elemMatch(
                    Criteria.where("userId").`is`(userId)
                        .and("isAbandoned").ne(true)
                        .and("isBot").ne(true),
                ),
        )
        return mongoTemplate.findOne(query, RoomDocument::class.java)
    }

    private fun findJoinablePublicRoom(principal: SessionPrincipal, maxPlayers: Int): Mono<RoomDocument> {
        val now = Instant.now(clock)
        return roomRepository.findAllByStatusOrderByCreatedAtAsc(RoomStatus.WAITING)
            .filter { room ->
                room.mode == RoomMode.ONLINE_PUBLIC &&
                    room.matchId == null &&
                    room.maxPlayers == maxPlayers &&
                    room.seats.size < room.maxPlayers &&
                    room.seats.none { it.userId == principal.id } &&
                    room.seats.none { it.isBot } &&
                    !hasSameSourceRealPlayer(room.seats, principal) &&
                    (
                        room.ownerInstanceId.isNullOrBlank() ||
                            room.ownerInstanceId == instanceCoordinator.instanceId
                        ) &&
                    (room.effectiveWaitingDeadlineAt() == null || now.isBefore(room.effectiveWaitingDeadlineAt()))
            }
            .collectList()
            .flatMapMany { candidates ->
                val waitingRealPlayerCount = candidates.sumOf { countRealSeats(it) }
                if (
                    !allowsPublicPvpMatchmaking(
                        waitingRealPlayerCount = waitingRealPlayerCount,
                        threshold = onlinePvpRealPlayerThreshold,
                    )
                ) {
                    Flux.empty()
                } else {
                    Flux.fromIterable(candidates)
                }
            }
            .next()
    }

    private fun leaveLobby(room: RoomDocument, userId: String): Mono<Void> {
        val now = Instant.now(clock)
        val remaining = room.seats.filter { it.userId != userId && !it.isBot }
        return if (remaining.isEmpty()) {
            roomRepository.delete(room).then()
        } else {
            val wasStarting = room.status == RoomStatus.STARTING
            val existingDeadline = room.effectiveWaitingDeadlineAt()
            // STARTING claims clear the deadline; restore an immediate due so remaining
            // players still get a bot-filled match instead of waiting another full lobby.
            val nextDeadline = when {
                wasStarting || existingDeadline == null -> now
                else -> existingDeadline
            }
            roomRepository.save(
                room.copy(
                    seats = normalizeSeatColors(remaining),
                    status = RoomStatus.WAITING,
                    startAttemptId = null,
                    // Strip any accidental bots when leaving a STARTING claim.
                    walletReservations = emptyList(),
                    waitingDeadlineAt = null,
                    ownedWaitingDeadlineAt = nextDeadline,
                    updatedAt = now,
                ),
            ).then()
        }
    }

    /**
     * Recover a hung STARTING room so the match can still start with bots.
     * Rooms with real wallet debits already in flight are finished (safer than double-charge).
     * Hung claims with no real reservations roll back to WAITING and retry start immediately.
     */
    private fun recoverHungStartingRoom(room: RoomDocument): Mono<Void> {
        val now = Instant.now(clock)
        val humans = room.seats.filter { !it.isBot && !it.isAbandoned }
        if (humans.isEmpty()) {
            return roomRepository.delete(room).then()
        }

        val hasRealReservation = room.walletReservations.any { reservation -> !reservation.synthetic }
        if (hasRealReservation) {
            log.warn(
                "Finishing hung online STARTING room with reservations roomId={} roomCode={} startAttemptId={}",
                room.id,
                room.code,
                room.startAttemptId,
            )
            return finishLobbyRoom(room).then()
        }

        log.warn(
            "Retrying hung online STARTING room with bot fill roomId={} roomCode={} startAttemptId={}",
            room.id,
            room.code,
            room.startAttemptId,
        )

        return roomRepository.save(
            room.copy(
                seats = normalizeSeatColors(humans),
                status = RoomStatus.WAITING,
                startAttemptId = null,
                walletReservations = emptyList(),
                waitingDeadlineAt = null,
                ownedWaitingDeadlineAt = now,
                ownerInstanceId = instanceCoordinator.instanceId,
                updatedAt = now,
            ),
        ).flatMap { waiting ->
            startPublicRoom(waiting.id)
                .doOnError { error -> logStartFailure(waiting, error) }
                .onErrorResume { Mono.empty() }
                .then()
        }
    }

    private fun finishLobbyRoom(room: RoomDocument): Mono<RoomDocument> {
        if (room.status == RoomStatus.FINISHED) {
            return Mono.just(room)
        }
        if (room.matchId != null && room.status == RoomStatus.ACTIVE) {
            return Mono.just(room)
        }
        return roomRepository.save(
            room.copy(
                status = RoomStatus.FINISHED,
                startAttemptId = null,
                seats = normalizeSeatColors(room.seats.filter { !it.isBot && !it.isAbandoned }),
                walletReservations = emptyList(),
                updatedAt = Instant.now(clock),
            ),
        )
    }

    private fun settleAndMarkRoomFinished(room: RoomDocument, match: MatchDocument): Mono<RoomDocument> {
        if (room.status == RoomStatus.FINISHED) {
            return Mono.just(room)
        }

        val settlement = match.winnerUserId
            ?.let { winnerUserId ->
                walletService.payoutWinner(
                    matchId = match.id,
                    winnerUserId = winnerUserId,
                    reservations = room.walletReservations,
                )
            }
            ?: Mono.empty()

        return settlement
            .onErrorResume { Mono.empty() }
            .then(
                roomRepository.save(
                    room.copy(
                        status = RoomStatus.FINISHED,
                        startAttemptId = null,
                        updatedAt = Instant.now(clock),
                    ),
                ),
            )
    }

    private fun releaseAbandonedUserFromActiveRoom(
        room: RoomDocument,
        match: MatchDocument,
        userId: String,
    ): Mono<RoomDocument> {
        val now = Instant.now(clock)
        val abandonedId = newId("abandoned")
        val updatedSeats = room.seats.map { seat ->
            if (seat.userId == userId && !seat.isBot && !seat.isAbandoned) {
                seat.copy(userId = abandonedId, isAbandoned = true, joinedAt = now)
            } else {
                seat
            }
        }
        val shouldFinishRoom = match.players.size == 2

        return roomRepository.save(
            room.copy(
                seats = updatedSeats,
                status = if (shouldFinishRoom) RoomStatus.FINISHED else room.status,
                updatedAt = now,
            ),
        ).flatMap { savedRoom ->
            if (!shouldFinishRoom) {
                return@flatMap Mono.just(savedRoom)
            }
            if (match.status == MatchStatus.FINISHED || match.phase == MatchPhase.FINISHED) {
                return@flatMap settleAndMarkRoomFinished(savedRoom, match)
            }

            val winner = match.players.firstOrNull { player ->
                player.userId != userId && !player.isEffectivelyAbandoned()
            }
            val finishedMatch = match.copy(
                status = MatchStatus.FINISHED,
                phase = MatchPhase.FINISHED,
                players = match.players.map { player ->
                    if (player.userId == userId && !player.isBot && !player.isEffectivelyAbandoned()) {
                        player.copy(userId = abandonedId, isAbandoned = true, tokens = emptyList())
                    } else {
                        player
                    }
                },
                winnerUserId = winner?.userId ?: match.winnerUserId,
                winnerDisplayName = winner?.displayName ?: match.winnerDisplayName,
                selectableTokenIndexes = emptyList(),
                pendingNextPlayerIndex = null,
                phaseDeadlineAt = null,
                turnDeadlineAt = null,
                updatedAt = now,
                sequence = match.sequence + 1,
            )
            matchRepository.save(finishedMatch)
                .flatMap { savedMatch -> settleAndMarkRoomFinished(savedRoom, savedMatch) }
        }
    }

    private fun reserveEntryFeesForSeats(
        realSeats: List<RoomSeat>,
        roomId: String,
        amount: Long,
        startAttemptId: String?,
    ): Mono<List<WalletReservation>> {
        val completed = mutableListOf<WalletReservation>()
        return Flux.fromIterable(realSeats)
            .concatMap { seat ->
                walletService.reserveEntryFee(
                    seat.userId,
                    roomId,
                    amount,
                    seat.ipAddress,
                    startAttemptId,
                ).doOnNext { completed.add(it) }
            }
            .collectList()
            .onErrorResume { error ->
                Flux.fromIterable(completed)
                    .concatMap { reservation ->
                        walletService.refundReservation(reservation, roomId).onErrorResume { Mono.empty() }
                    }
                    .then(Mono.error(error))
            }
    }

    private fun refundReservations(reservations: List<WalletReservation>, roomId: String): Mono<Void> {
        if (reservations.isEmpty()) return Mono.empty()
        return Flux.fromIterable(reservations)
            .concatMap { reservation ->
                walletService.refundReservation(reservation, roomId).onErrorResume { Mono.empty() }
            }
            .then()
    }

    private fun syntheticBotFees(botSeats: List<RoomSeat>, amount: Long): List<WalletReservation> {
        if (amount <= 0) return emptyList()
        return botSeats.map { seat ->
            WalletReservation(
                userId = seat.userId,
                transactionId = newId("botfee"),
                amount = amount,
                synthetic = true,
                ipAddress = seat.ipAddress,
            )
        }
    }

    private fun rejectSameSourceRealPlayer(
        existingSeats: List<RoomSeat>,
        principal: SessionPrincipal,
        roomId: String,
        roomCode: String,
    ) {
        val conflict = existingSeats.firstOrNull { seat -> isSameSourceRealPlayerSeat(seat, principal) } ?: return
        log.warn(
            "Blocked Ludo same-source room join roomId={} roomCode={} userId={} existingUserId={} reason={}",
            roomId,
            roomCode,
            principal.id,
            conflict.userId,
            sameSourceReasonForSeat(conflict, principal),
        )
        throw DomainException(
            HttpStatus.CONFLICT,
            "This Ludo room already has a player from the same source. Please join another Ludo room.",
        )
    }

    private fun hasSameSourceRealPlayer(existingSeats: List<RoomSeat>, principal: SessionPrincipal): Boolean =
        existingSeats.any { seat -> isSameSourceRealPlayerSeat(seat, principal) }

    private fun logStartFailure(room: RoomDocument, error: Throwable) {
        log.error(
            "Failed to start online room roomId={} roomCode={} entryFee={} realSeats={} botSeats={} reason={}",
            room.id,
            room.code,
            room.entryFee,
            room.seats.count { !it.isBot },
            room.seats.count { it.isBot },
            error.message ?: error.javaClass.simpleName,
            error,
        )
    }
}
