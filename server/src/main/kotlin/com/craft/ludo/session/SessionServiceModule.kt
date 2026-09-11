package com.craft.ludo.session

import com.craft.ludo.provider.ProviderGameSdkClient
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.repository.ReactiveMongoRepository
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant

object SessionGameEvents {
    const val TABLE_CREATED = "TABLE_CREATED"
    const val ROUND_CREATED = "ROUND_CREATED"
    const val ROUND_STARTED = "ROUND_STARTED"
    const val ROUND_ENDED = "ROUND_ENDED"
    const val ROUND_CANCELLED = "ROUND_CANCELLED"
}

data class ValidatedSession(
    val sessionToken: String,
    val userId: String,
    val displayName: String,
    val balance: BigDecimal = BigDecimal.ZERO,
    val currency: String = "INR",
    val operatorUserId: String? = null,
    val operatorId: String? = null,
    val gameId: Int? = null,
)

@Document("external_session_bindings")
data class ExternalSessionBindingDocument(
    @Id
    val sessionToken: String,
    val userId: String,
    val displayName: String,
    val operatorUserId: String? = null,
    val operatorId: String? = null,
    val operatorGameId: Int? = null,
    val validatedAt: Instant,
)

@Repository
interface ExternalSessionBindingRepository : ReactiveMongoRepository<ExternalSessionBindingDocument, String> {
    fun findFirstByUserIdOrderByValidatedAtDesc(userId: String): Mono<ExternalSessionBindingDocument>
}

@Service
class SessionBindingService(
    private val providerGameSdkClient: ProviderGameSdkClient,
    private val bindingRepository: ExternalSessionBindingRepository,
    private val clock: Clock,
) {
    fun isEnabled(): Boolean = providerGameSdkClient.isEnabled()

    fun validateAndBind(sessionToken: String, fallbackGameId: Int? = null): Mono<ValidatedSession> {
        return providerGameSdkClient.validateSession(sessionToken)
            .flatMap { validated ->
                val binding = ExternalSessionBindingDocument(
                    sessionToken = validated.sessionToken,
                    userId = validated.userId,
                    displayName = validated.displayName,
                    operatorUserId = validated.operatorUserId ?: validated.userId,
                    operatorId = validated.operatorId,
                    operatorGameId = validated.gameId ?: fallbackGameId,
                    validatedAt = Instant.now(clock),
                )
                bindingRepository.save(binding).thenReturn(validated)
            }
    }

    fun findBindingBySessionToken(sessionToken: String): Mono<ExternalSessionBindingDocument> {
        return bindingRepository.findById(sessionToken.trim())
    }

    fun findBindingByUserId(userId: String): Mono<ExternalSessionBindingDocument> {
        return bindingRepository.findFirstByUserIdOrderByValidatedAtDesc(userId)
    }
}

@Service
class SessionLifecyclePublisher(
    private val providerGameSdkClient: ProviderGameSdkClient,
) {
    /**
     * Provider expects table + round to exist before wallet debit / round start.
     * Best-effort per token; failures are logged but do not block match start.
     */
    fun publishMatchLifecycleBeforeStart(
        matchId: String,
        roomId: String,
        roomCode: String,
        entryFee: Long,
        sessionTokens: List<String>,
    ): Mono<Void> {
        if (!providerGameSdkClient.isEnabled() || sessionTokens.isEmpty()) {
            return Mono.empty()
        }

        val tablePayload = mapOf(
            "roomId" to roomId,
            "roomCode" to roomCode,
        )
        val roundPayload = mapOf(
            "entryFee" to entryFee,
            "roomId" to roomId,
            "roomCode" to roomCode,
        )

        return Flux.fromIterable(sessionTokens.distinct())
            .concatMap { token ->
                providerGameSdkClient.publishEvent(
                    sessionToken = token,
                    event = SessionGameEvents.TABLE_CREATED,
                    tableId = roomId,
                    payload = tablePayload,
                )
            }
            .then(
                Flux.fromIterable(sessionTokens.distinct())
                    .concatMap { token ->
                        providerGameSdkClient.publishEvent(
                            sessionToken = token,
                            event = SessionGameEvents.ROUND_CREATED,
                            roundId = matchId,
                            tableId = roomId,
                            payload = roundPayload,
                        )
                    }
                    .then(),
            )
    }

    fun publishRoundStarted(
        matchId: String,
        entryFee: Long,
        roomId: String,
        roomCode: String,
        sessionTokens: List<String>,
    ): Mono<Void> {
        if (!providerGameSdkClient.isEnabled() || sessionTokens.isEmpty()) {
            return Mono.empty()
        }

        val payload = mapOf(
            "betAmount" to entryFee,
            "roomId" to roomId,
            "roomCode" to roomCode,
        )

        return Flux.fromIterable(sessionTokens.distinct())
            .flatMap { token ->
                providerGameSdkClient.publishEvent(
                    sessionToken = token,
                    event = SessionGameEvents.ROUND_STARTED,
                    roundId = matchId,
                    tableId = roomId,
                    payload = payload,
                )
            }
            .then()
    }

    fun publishRoundFinished(
        matchId: String,
        entryFee: Long,
        potAmount: Long,
        winnerUserId: String?,
        winnerDisplayName: String?,
        roomId: String,
        roomCode: String,
        sessionTokens: List<String>,
    ): Mono<Void> {
        if (!providerGameSdkClient.isEnabled() || sessionTokens.isEmpty()) {
            return Mono.empty()
        }

        val payload = mapOf(
            "betAmount" to entryFee,
            "potAmount" to potAmount,
            "winnerUserId" to winnerUserId,
            "winnerDisplayName" to winnerDisplayName,
            "roomId" to roomId,
            "roomCode" to roomCode,
        )

        return Flux.fromIterable(sessionTokens.distinct())
            .flatMap { token ->
                providerGameSdkClient.publishEvent(
                    sessionToken = token,
                    event = SessionGameEvents.ROUND_ENDED,
                    roundId = matchId,
                    tableId = roomId,
                    payload = payload,
                )
            }
            .then()
    }

    fun publishRoundCancelled(
        roundId: String,
        reason: String,
        sessionTokens: List<String>,
    ): Mono<Void> {
        if (!providerGameSdkClient.isEnabled() || sessionTokens.isEmpty()) {
            return Mono.empty()
        }

        val payload = mapOf(
            "reason" to reason,
        )

        return Flux.fromIterable(sessionTokens.distinct())
            .flatMap { token ->
                providerGameSdkClient.publishEvent(
                    sessionToken = token,
                    event = SessionGameEvents.ROUND_CANCELLED,
                    roundId = roundId,
                    payload = payload,
                )
            }
            .then()
    }
}
