package com.craft.ludo.tournament

import com.craft.ludo.shared.api.DomainException
import org.bson.Document
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.index.Index
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.reactive.TransactionalOperator
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.util.retry.Retry
import org.springframework.dao.OptimisticLockingFailureException
import com.mongodb.MongoException
import java.time.Duration
import java.time.Instant

@Component
class TournamentStore(val mongo: ReactiveMongoTemplate, private val transaction: TransactionalOperator) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        // Fail startup if these invariants cannot be installed, rather than silently
        // admitting duplicate registrations or match/reward records.
        val indexes = mapOf(
            "tournament_registrations" to listOf("tournamentId", "userId"),
            "tournament_participants" to listOf("tournamentId", "userId"),
            "tournament_matches" to listOf("tournamentId", "roundNumber", "matchNumber"),
            "tournament_rounds" to listOf("tournamentId", "number"),
            "tournament_rewards" to listOf("tournamentId", "userId", "rewardType"),
        )
        Flux.fromIterable(indexes.entries).concatMap { (collection, fields) ->
            var index = Index()
            fields.forEach { index = index.on(it, Sort.Direction.ASC) }
            mongo.indexOps(collection).ensureIndex(index.unique())
        }.then().block(Duration.ofSeconds(30))
    }

    fun get(id: String): Mono<TournamentDocument> = mongo.findById(id, TournamentDocument::class.java)
        .switchIfEmpty(Mono.error(DomainException(HttpStatus.NOT_FOUND, "Tournament not found.")))

    fun all(): Flux<TournamentDocument> = mongo.find(Query().with(Sort.by(Sort.Direction.DESC, "createdAt")), TournamentDocument::class.java)

    fun save(document: TournamentDocument, before: TournamentDocument? = null): Mono<TournamentDocument> = mongo.save(document).flatMap { saved ->
        // The versioned aggregate serializes capacity, progression, and settlement.
        // Queryable projections and audit records commit in the SAME Mongo transaction.
        Flux.concat(
            Flux.fromIterable(saved.registrations.filterNot { it in before?.registrations.orEmpty() }).concatMap { mongo.save(it) },
            Flux.fromIterable(saved.participants.filterNot { it in before?.participants.orEmpty() }).concatMap { mongo.save(it) },
            Flux.fromIterable(saved.rounds.filterNot { it in before?.rounds.orEmpty() }).concatMap { mongo.save(it) },
            Flux.fromIterable(saved.matches.filterNot { it in before?.matches.orEmpty() }).concatMap { mongo.save(it) },
            Flux.fromIterable(saved.rewards.filterNot { it in before?.rewards.orEmpty() }).concatMap { mongo.save(it) },
            Flux.fromIterable(saved.events.filterNot { it in before?.events.orEmpty() }).concatMap { mongo.save(it) },
        ).then(Mono.just(saved))
    }

    fun create(document: TournamentDocument): Mono<TournamentDocument> = transaction.transactional(save(document))

    fun delete(id: String): Mono<Void> = transaction.transactional(
        Flux.concat(
            mongo.remove(Query(Criteria.where("_id").`is`(id)), TournamentDocument::class.java),
            mongo.remove(Query(Criteria.where("tournamentId").`is`(id)), "tournament_registrations"),
            mongo.remove(Query(Criteria.where("tournamentId").`is`(id)), "tournament_participants"),
            mongo.remove(Query(Criteria.where("tournamentId").`is`(id)), "tournament_rounds"),
            mongo.remove(Query(Criteria.where("tournamentId").`is`(id)), "tournament_matches"),
            mongo.remove(Query(Criteria.where("tournamentId").`is`(id)), "tournament_rewards"),
            mongo.remove(Query(Criteria.where("tournamentId").`is`(id)), "tournament_audit_logs"),
            mongo.remove(Query(Criteria.where("tournamentId").`is`(id)), "tournament_player_locks"),
        ).then()
    )

    fun mutate(id: String, change: (TournamentDocument) -> Mono<TournamentDocument>): Mono<TournamentDocument> =
        Mono.defer {
            transaction.transactional(get(id).flatMap { before ->
                change(before).flatMap { after -> if (after == before) Mono.just(before) else save(after, before) }
            })
        }.retryWhen(Retry.backoff(4, Duration.ofMillis(40)).filter { error ->
            generateSequence(error) { it.cause }.any { it is OptimisticLockingFailureException || (it is MongoException && it.hasErrorLabel("TransientTransactionError")) }
        }.onRetryExhaustedThrow { _, signal ->
            DomainException(HttpStatus.CONFLICT, "Tournament changed concurrently. Please retry.")
        })
}

@Component
class TournamentAdminAuth(private val mongo: ReactiveMongoTemplate) {
    fun requireAdmin(authorization: String): Mono<String> {
        val token = authorization.removePrefix("Bearer ").trim()
        if (!authorization.startsWith("Bearer ") || token.isEmpty()) return Mono.error(DomainException(HttpStatus.UNAUTHORIZED, "Admin login required."))
        return mongo.findOne(Query(Criteria.where("token").`is`(token).and("expiresAt").gt(Instant.now())), Document::class.java, "admin_sessions")
            .flatMap { session ->
                val adminId = session["adminId"] ?: return@flatMap Mono.empty<String>()
                mongo.findOne(Query(Criteria.where("_id").`is`(adminId)), Document::class.java, "admin_accounts")
                    .filter { account -> account["disabled"] != true && (account["role"] == null || account["role"] in listOf("ADMIN", "SUPER_ADMIN", "admin")) }
                    .map { adminId.toString() }
            }.switchIfEmpty(Mono.error(DomainException(HttpStatus.UNAUTHORIZED, "Valid administrator session required.")))
    }
}
