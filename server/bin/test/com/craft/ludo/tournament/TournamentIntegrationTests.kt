package com.craft.ludo.tournament

import com.craft.ludo.gameplay.*
import com.craft.ludo.identity.IdentityService
import com.craft.ludo.identity.CreateGuestSessionRequest
import com.craft.ludo.identity.SessionPrincipal
import org.assertj.core.api.Assertions.assertThat
import org.bson.Document
import org.junit.jupiter.api.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.UUID
import reactor.core.publisher.Mono

/** Opt-in: uses configured Mongo/Redis, but ONLY a randomly named disposable database. */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "RUN_TOURNAMENT_INTEGRATION", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["app.session-service.enabled=false", "logging.level.root=ERROR"])
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TournamentIntegrationTests {
    companion object {
        private val database = "ludo_ttest_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        @JvmStatic @DynamicPropertySource fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.mongodb.database") { database }
            registry.add("app.realtime.redis-channel") { database }
            registry.add("app.instance.lock-key-prefix") { "$database:lock" }
            registry.add("app.instance.heartbeat-key-prefix") { "$database:instance" }
        }
    }
    @Autowired lateinit var mongo: ReactiveMongoTemplate
    @Autowired lateinit var service: TournamentService
    @Autowired lateinit var identity: IdentityService
    @Autowired lateinit var matches: MatchRepository
    @LocalServerPort var port: Int = 0
    private lateinit var http: WebTestClient
    private val adminToken = UUID.randomUUID().toString()
    @BeforeAll fun setup() {
        http = WebTestClient.bindToServer().baseUrl("http://localhost:$port").responseTimeout(Duration.ofSeconds(30)).build()
        val adminId = org.bson.types.ObjectId()
        mongo.insert(Document("_id", adminId).append("role", "ADMIN"), "admin_accounts").block()
        mongo.insert(Document("token", adminToken).append("adminId", adminId).append("expiresAt", java.util.Date.from(Instant.now().plusSeconds(3600))), "admin_sessions").block()
    }
    @AfterAll fun cleanup() { mongo.mongoDatabase.flatMap { Mono.from(it.drop()) }.block(Duration.ofSeconds(30)) }
    private fun config(format: Int = 2, capacity: Int = 4) = TournamentConfig("Integration Cup", gameFormat = format, maxParticipants = capacity,
        prizePoints = 100, registrationStart = Instant.now().minusSeconds(30), registrationEnd = Instant.now().plusSeconds(600), startAt = Instant.now().plusSeconds(700))
    private fun player(): SessionPrincipal {
        val s = identity.createGuestSession(CreateGuestSessionRequest("Test Player")).block()!!
        return SessionPrincipal(s.userId, s.sessionToken, s.displayName)
    }
    private fun cup(format: Int = 2, capacity: Int = 4): TournamentDocument {
        val t = service.create(config(format, capacity), "test-admin").block()!!
        return service.publish(t.id, "test-admin").block()!!
    }
    private fun close(tid: String) {
        val t = service.get(tid).block()!!
        mongo.save(t.copy(status = TournamentStatus.REGISTRATION_CLOSED)).block()
    }
    private fun awaitState(id: String, condition: (TournamentDocument) -> Boolean): TournamentDocument {
        val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
        while (System.nanoTime() < deadline) {
            val t = service.get(id).block()!!
            if (condition(t)) return t
            Thread.sleep(100)
        }
        error("Tournament did not reach expected state: ${service.get(id).block()?.status}")
    }
    @Test fun `admin authentication and payment gates are enforced over HTTP`() {
        http.get().uri("/api/admin/tournaments").header("Authorization", "Bearer invalid").exchange().expectStatus().isUnauthorized
        http.get().uri("/api/admin/tournaments").header("Authorization", "Bearer $adminToken").exchange().expectStatus().isOk
        http.post().uri("/api/tournament-payments/create").exchange().expectStatus().isEqualTo(423)
        val t = service.create(config().copy(entryType = EntryType.PAID, entryFee = 100), "test-admin").block()!!
        http.post().uri("/api/admin/tournaments/${t.id}/publish").header("Authorization", "Bearer $adminToken").exchange().expectStatus().isOk
        assertThat(service.get(t.id).block()!!.status).isEqualTo(TournamentStatus.REGISTRATION_OPEN)
    }
    @Test fun `concurrent registration cannot exceed capacity and duplicate registration is idempotent`() {
        val t = cup()
        val players = (1..12).map { player() }
        val jobs = players.map { p -> CompletableFuture.runAsync {
            runCatching { service.register(t.id, p).block() }
        } }
        CompletableFuture.allOf(*jobs.toTypedArray()).get()
        val registered = service.get(t.id).block()!!
        assertThat(registered.registeredParticipants).isEqualTo(4)
        assertThat(registered.registrations.map { it.userId }.distinct()).hasSize(4)
        val p = players.first { it.id == registered.registrations.first().userId }
        repeat(3) { service.register(t.id, p).block() }
        assertThat(service.get(t.id).block()!!.registeredParticipants).isEqualTo(4)
        assertThat(mongo.count(org.springframework.data.mongodb.core.query.Query(org.springframework.data.mongodb.core.query.Criteria.where("tournamentId").`is`(t.id)), TournamentRegistration::class.java).block()).isEqualTo(4)
    }
    @Test fun `two rounds advance only from authoritative games and settle points once`() {
        val t = cup()
        val players = (1..4).map { player() }
        players.forEach { service.register(t.id, it).block() }
        close(t.id)
        service.start(t.id, "test-admin").block()
        repeat(2) { round ->
            val ready = awaitState(t.id) { it.currentRound == round + 1 }
            ready.matches.filter { it.roundNumber == round + 1 }.forEach { m ->
                val stranger = player()
                http.post().uri("/api/tournament-matches/${m.id}/join").header("X-Session-Token", stranger.sessionToken).exchange().expectStatus().isForbidden
                m.assignedPlayerIds.forEach { uid -> service.joinMatch(m.id, players.first { it.id == uid }).block() }
            }
            val running = awaitState(t.id) { it.matches.filter { m -> m.roundNumber == round + 1 }.all { m -> m.status == TournamentMatchStatus.IN_PROGRESS } }
            running.matches.filter { it.roundNumber == round + 1 }.forEach { m ->
                val game = matches.findById(m.gameSessionId!!).block()!!
                assertThat(game.mode).isEqualTo(RoomMode.TOURNAMENT)
                assertThat(game.entryFee).isZero()
                // Simulate a persisted engine completion; no public result-submission API exists.
                matches.save(game.copy(status = MatchStatus.FINISHED, phase = MatchPhase.FINISHED, winnerUserId = m.assignedPlayerIds.first(),
                    tournamentFinishingOrder = m.assignedPlayerIds, turnDeadlineAt = null, phaseDeadlineAt = null, sequence = game.sequence + 1)).block()
            }
        }
        val completed = awaitState(t.id) { it.status == TournamentStatus.COMPLETED }
        assertThat(completed.rewards).hasSize(1)
        assertThat(completed.rewards.single().amount).isEqualTo(100)
        assertThat(completed.matches).allMatch { it.resultVerified }
        repeat(3) { service.start(t.id, "test-admin").block() }
        assertThat(service.get(t.id).block()!!.rewards).hasSize(1)
        assertThat(mongo.count(org.springframework.data.mongodb.core.query.Query(org.springframework.data.mongodb.core.query.Criteria.where("tournamentId").`is`(t.id)), TournamentReward::class.java).block()).isEqualTo(1)
    }
    @Test fun `four player top two qualifiers fill final and ranking determines champion`() {
        val t = cup(4, 8)
        val players = (1..8).map { player() }
        players.forEach { service.register(t.id, it).block() }
        close(t.id)
        service.start(t.id, "test-admin").block()
        val ready = service.get(t.id).block()!!
        assertThat(ready.matches.filter { it.roundNumber == 1 }).allMatch { it.qualifierCount == 2 }
        ready.matches.filter { it.roundNumber == 1 }.forEach { m ->
            m.assignedPlayerIds.forEach { uid -> service.joinMatch(m.id, players.first { it.id == uid }).block() }
        }
        val running = awaitState(t.id) { it.matches.filter { m -> m.roundNumber == 1 }.all { m -> m.status == TournamentMatchStatus.IN_PROGRESS } }
        val expected = running.matches.filter { it.roundNumber == 1 }.flatMap { it.assignedPlayerIds.reversed().take(2) }
        running.matches.filter { it.roundNumber == 1 }.forEach { m ->
            val game = matches.findById(m.gameSessionId!!).block()!!
            val order = m.assignedPlayerIds.reversed()
            matches.save(game.copy(status = MatchStatus.FINISHED, phase = MatchPhase.FINISHED,
                winnerUserId = order.first(), tournamentFinishingOrder = order, sequence = game.sequence + 1)).block()
        }
        val final = awaitState(t.id) { it.currentRound == 2 }
        assertThat(final.matches.single { it.roundNumber == 2 }.assignedPlayerIds).containsExactlyInAnyOrderElementsOf(expected)
        service.cancel(t.id, "test-admin", "Integration test cleanup").block()
    }

    @Test fun `no show forfeits to checked in player and abandoned cup releases seats`() {
        val t = cup(2, 2)
        val players = (1..2).map { player() }
        players.forEach { service.register(t.id, it).block() }
        close(t.id); service.start(t.id, "test-admin").block()
        val ready = service.get(t.id).block()!!
        val m = ready.matches.single()
        service.joinMatch(m.id, players.first()).block()
        val checked = service.get(t.id).block()!!
        mongo.save(checked.copy(matches = checked.matches.map { it.copy(joinDeadline = Instant.now().minusSeconds(1)) })).block()
        val completed = awaitState(t.id) { it.status == TournamentStatus.COMPLETED }
        assertThat(completed.championId).isEqualTo(players.first().id)
        assertThat(completed.matches.single().status).isEqualTo(TournamentMatchStatus.NO_SHOW)
        assertThat(mongo.findById(players.first().id, Document::class.java, "active_game_players").block()).isNull()
    }

    @Test fun `same player cannot check into simultaneous tournament matches`() {
        val first = cup(2, 2)
        val second = cup(2, 2)
        val shared = player()
        for (t in listOf(first, second)) {
            service.register(t.id, shared).block(); service.register(t.id, player()).block()
            close(t.id); service.start(t.id, "test-admin").block()
        }
        val firstMatch = service.get(first.id).block()!!.matches.single()
        val secondMatch = service.get(second.id).block()!!.matches.single()
        service.joinMatch(firstMatch.id, shared).block()
        http.post().uri("/api/tournament-matches/${secondMatch.id}/join").header("X-Session-Token", shared.sessionToken).exchange().expectStatus().isEqualTo(409)
        service.cancel(first.id, "test-admin", "Release checked in participant").block()
        service.joinMatch(secondMatch.id, shared).block()
        service.cancel(second.id, "test-admin", "Integration test cleanup").block()
    }

}
