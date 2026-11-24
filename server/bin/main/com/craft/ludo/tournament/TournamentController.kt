package com.craft.ludo.tournament

import com.craft.ludo.identity.SessionPrincipalResolver
import com.craft.ludo.shared.api.DomainException
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono

internal fun tournamentSummary(t: TournamentDocument) = mapOf(
    "id" to t.id, "config" to t.config, "status" to t.status, "registeredParticipants" to t.registeredParticipants,
    "currentRound" to t.currentRound, "championId" to t.championId, "version" to t.version,
    "paidEntryEnabled" to TournamentPolicy.PAID_ENTRY_ENABLED, "cashPrizesEnabled" to TournamentPolicy.CASH_PRIZES_ENABLED,
)
internal fun tournamentView(t: TournamentDocument) = tournamentSummary(t) + mapOf(
    "participants" to t.participants, "rounds" to t.rounds, "matches" to t.matches, "rewards" to t.rewards,
    "events" to t.events.map { mapOf("id" to it.id, "type" to it.action, "createdAt" to it.createdAt, "message" to it.reason) },
)

@RestController
@RequestMapping("/api/tournaments")
class TournamentController(private val service: TournamentService, private val bracket: TournamentBracketService, private val identity: SessionPrincipalResolver) {
    @GetMapping fun list(@RequestParam(required = false) status: TournamentStatus?) = service.list().filter { status == null || it.status == status }.map(::tournamentSummary)
    @GetMapping("/my") fun mine(@RequestHeader("X-Session-Token") token: String) = identity.requireUser(token).flatMapMany { service.mine(it.id).map(::tournamentSummary) }
    @GetMapping("/{id}") fun details(@PathVariable id: String) = service.get(id).map { t ->
        if (t.status == TournamentStatus.DRAFT) throw DomainException(HttpStatus.NOT_FOUND, "Tournament not found.")
        tournamentView(t)
    }
    @GetMapping("/{id}/bracket") fun bracket(@PathVariable id: String) = details(id).then(service.get(id)).map { t ->
        mapOf("preview" to bracket.preview(t.config.maxParticipants, t.config.gameFormat, t.config.qualifiersPerRound), "rounds" to t.rounds, "matches" to t.matches)
    }
    @GetMapping("/{id}/players") fun players(@PathVariable id: String) = details(id).map { it["participants"] }
    @GetMapping("/{id}/results") fun results(@PathVariable id: String) = details(id).map { mapOf("status" to it["status"], "championId" to it["championId"], "participants" to it["participants"], "rewards" to it["rewards"]) }
    @PostMapping("/{id}/register") fun register(@PathVariable id: String, @RequestHeader("X-Session-Token") token: String) = identity.requireUser(token).flatMap { service.register(id, it) }.map(::tournamentView)
    @PostMapping("/{id}/cancel-registration") fun cancelRegistration(@PathVariable id: String, @RequestHeader("X-Session-Token") token: String) = identity.requireUser(token).flatMap { service.cancelRegistration(id, it) }.map(::tournamentView)
}

@RestController
@RequestMapping("/api/tournament-matches")
class TournamentMatchController(private val service: TournamentService, private val identity: SessionPrincipalResolver) {
    @GetMapping("/my") fun mine(@RequestHeader("X-Session-Token") token: String) = identity.requireUser(token).flatMapMany { user -> service.mine(user.id).flatMapIterable { it.matches.filter { m -> user.id in m.assignedPlayerIds } } }
    @GetMapping("/{id}", "/{id}/status") fun details(@PathVariable id: String, @RequestHeader("X-Session-Token") token: String) = identity.requireUser(token).flatMap { user -> service.findMatch(id).map { (_, m) ->
        if (user.id !in m.assignedPlayerIds) throw DomainException(HttpStatus.FORBIDDEN, "You are not assigned to this match.")
        m
    } }
    @PostMapping("/{id}/join") fun join(@PathVariable id: String, @RequestHeader("X-Session-Token") token: String) = identity.requireUser(token).flatMap { service.joinMatch(id, it) }.map(::tournamentView)
}

@RestController
class TournamentRewardsController(private val service: TournamentService, private val identity: SessionPrincipalResolver) {
    @GetMapping("/api/tournament-rewards/my") fun rewards(@RequestHeader("X-Session-Token") token: String) = identity.requireUser(token).flatMapMany { user -> service.mine(user.id).flatMapIterable { it.rewards.filter { r -> r.userId == user.id } } }
    @RequestMapping("/api/tournament-payments/create", "/api/tournament-payments/webhook", "/api/tournament-payments/{id}", "/api/tournament-payments/{id}/refund", method = [RequestMethod.GET, RequestMethod.POST])
    fun payments(): Mono<Void> = Mono.error(DomainException(HttpStatus.LOCKED, "Paid entry, payments, refunds and cash prizes are not enabled. No money has been collected."))
}

@RestController
@RequestMapping("/api/admin/tournaments")
class TournamentAdminController(private val service: TournamentService, private val auth: TournamentAdminAuth, private val store: TournamentStore, private val bracket: TournamentBracketService) {
    @GetMapping fun list(@RequestHeader("Authorization") token: String) = auth.requireAdmin(token).flatMapMany { service.list(true) }
    @GetMapping("/{id}") fun details(@PathVariable id: String, @RequestHeader("Authorization") token: String) = auth.requireAdmin(token).flatMap { service.get(id) }
    @PostMapping fun create(@RequestHeader("Authorization") token: String, @RequestBody config: TournamentConfig) = auth.requireAdmin(token).flatMap { service.create(config, it) }
    @PutMapping("/{id}") fun update(@PathVariable id: String, @RequestHeader("Authorization") token: String, @RequestBody config: TournamentConfig) = auth.requireAdmin(token).flatMap { service.update(id, config, it) }
    @DeleteMapping("/{id}") fun delete(@PathVariable id: String, @RequestHeader("Authorization") token: String) = auth.requireAdmin(token).flatMap { service.delete(id, it) }
    @PostMapping("/preview") fun preview(@RequestHeader("Authorization") token: String, @RequestBody config: TournamentConfig) = auth.requireAdmin(token).map { service.validate(config); bracket.preview(config.maxParticipants, config.gameFormat, config.qualifiersPerRound) }
    @PostMapping("/{id}/publish") fun publish(@PathVariable id: String, @RequestHeader("Authorization") token: String) = auth.requireAdmin(token).flatMap { service.publish(id, it) }
    @PostMapping("/{id}/start") fun start(@PathVariable id: String, @RequestHeader("Authorization") token: String) = auth.requireAdmin(token).flatMap { service.start(id, it) }
    @PostMapping("/{id}/cancel") fun cancel(@PathVariable id: String, @RequestHeader("Authorization") token: String, @RequestBody action: TournamentAdminAction) = auth.requireAdmin(token).flatMap { service.cancel(id, it, action.reason) }
    @PostMapping("/{id}/{action:disqualify|restart-match|resolve-match|force-complete}") fun intervene(@PathVariable id: String, @PathVariable action: String, @RequestHeader("Authorization") token: String, @RequestBody request: TournamentAdminAction) = auth.requireAdmin(token).flatMap { service.intervene(id, it, action, request) }
    @GetMapping("/{id}/analytics") fun analytics(@PathVariable id: String, @RequestHeader("Authorization") token: String) = auth.requireAdmin(token).flatMap { service.get(id) }.map { t -> mapOf(
        "participants" to t.registeredParticipants, "matches" to t.matches.size, "completedMatches" to t.matches.count { it.resultVerified },
        "disputedMatches" to t.matches.count { it.status == TournamentMatchStatus.DISPUTED },
        "verifiedEntryCollections" to t.registrations.filter { it.paymentStatus == "PAID" }.sumOf { it.entryFee },
        "pendingRewards" to t.rewards.count { it.status != "SETTLED" }, "settledPoints" to t.rewards.sumOf { it.amount },
    ) }
    @GetMapping("/{id}/audit") fun audit(@PathVariable id: String, @RequestHeader("Authorization") token: String) = auth.requireAdmin(token).flatMapMany {
        store.mongo.find(Query(Criteria.where("tournamentId").`is`(id)).with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(500), TournamentAudit::class.java)
    }
}
