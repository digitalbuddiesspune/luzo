package com.craft.ludo.gameplay

import com.craft.ludo.shared.api.DomainException
import org.bson.Document
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/** One durable seat per human across Classic, Custom and tournament sessions. */
@Service
class GameParticipationGate(private val mongo: ReactiveMongoTemplate) {
    fun acquire(userId: String, gameId: String): Mono<Void> = mongo.findAndModify(
        Query(Criteria.where("_id").`is`(userId).and("gameId").`is`(gameId)),
        Update().setOnInsert("gameId", gameId).setOnInsert("createdAt", java.time.Instant.now()), FindAndModifyOptions.options().upsert(true).returnNew(true),
        Document::class.java, "active_game_players",
    ).then().onErrorResume(DuplicateKeyException::class.java) {
        // Some legacy leave paths persist their game directly. Recover those locks
        // only after checking the authoritative old game; pending check-ins stay locked.
        mongo.findById(userId, Document::class.java, "active_game_players").flatMap { occupied ->
            val previous = occupied.getString("gameId")
            mongo.findById(previous, MatchDocument::class.java)
                .filter { it.status == MatchStatus.FINISHED || it.players.none { p -> p.userId == userId && !p.isAbandoned } }
                .flatMap {
                    mongo.remove(Query(Criteria.where("_id").`is`(userId).and("gameId").`is`(previous)), "active_game_players")
                        .flatMap { removed -> if (removed.deletedCount == 1L) acquire(userId, gameId).thenReturn(true) else Mono.just(false) }
                }.defaultIfEmpty(false)
        }.defaultIfEmpty(false).flatMap { recovered ->
            if (recovered) Mono.empty() else Mono.error(DomainException(HttpStatus.CONFLICT, "Player is already assigned to another active game."))
        }
    }

    fun acquire(room: RoomDocument, gameId: String): Mono<Void> = Flux.fromIterable(room.seats.filter { !it.isBot && !it.isAbandoned })
        .concatMap { acquire(it.userId, gameId) }.then()

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60000)
    fun recoverAbortedStarts() {
        val stale = Query(Criteria.where("createdAt").lt(java.time.Instant.now().minusSeconds(120)))
        mongo.find(stale, Document::class.java, "active_game_players").concatMap { claim ->
            val userId = claim.getString("_id")
            val gameId = claim.getString("gameId")
            mongo.exists(Query(Criteria.where("_id").`is`(gameId)), MatchDocument::class.java).flatMap { exists ->
                if (exists) Mono.empty() else mongo.exists(Query(Criteria.where("_id").`is`(userId)), "tournament_player_locks").flatMap { checkedIn ->
                    if (checkedIn) Mono.empty() else mongo.remove(Query(Criteria.where("_id").`is`(userId).and("gameId").`is`(gameId)), "active_game_players").then()
                }
            }
        }.subscribe({}, { error -> org.slf4j.LoggerFactory.getLogger(javaClass).warn("Aborted game seat recovery failed", error) })
    }

    fun release(gameId: String): Mono<Void> = mongo.remove(Query(Criteria.where("gameId").`is`(gameId)), "active_game_players").then()

    fun sync(game: MatchDocument): Mono<Void> = if (game.status == MatchStatus.FINISHED) release(game.id) else
        mongo.remove(Query(Criteria.where("gameId").`is`(game.id).and("_id").nin(game.players.filter { !it.isAbandoned }.map { it.userId })), "active_game_players").then()
}
