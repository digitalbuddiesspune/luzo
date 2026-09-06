package com.craft.ludo.session

import com.craft.ludo.shared.api.DomainException
import com.craft.ludo.shared.config.AppProperties
import com.craft.ludo.shared.config.AppProperties
import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.repository.ReactiveMongoRepository
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant

object SessionGameEvents {
    const val ROUND_STARTED = "ROUND_STARTED"
    const val ROUND_FINISHED = "ROUND_FINISHED"
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
class SessionServiceClient(
    webClientBuilder: WebClient.Builder,
    appProperties: AppProperties,
) {
    private val log = LoggerFactory.getLogger(SessionServiceClient::class.java)
    private val properties = appProperties.sessionService
    private val baseUrl = properties.baseUrl.trimEnd('/')
    private val webClient = webClientBuilder
        .baseUrl(baseUrl)
        .build()

    fun isEnabled(): Boolean = properties.enabled

    fun validateSession(sessionToken: String): Mono<ValidatedSession> {
        if (!properties.enabled) {
            return Mono.error(
                DomainException(HttpStatus.SERVICE_UNAVAILABLE, "External session service is disabled."),
            )
        }

        val normalizedToken = sessionToken.trim()
        if (normalizedToken.isBlank()) {
            return Mono.error(DomainException(HttpStatus.BAD_REQUEST, "sessionToken is required."))
        }

        val validatePath = properties.validatePath.trim().ifBlank { "/api/v1/sessions/validate" }
        log.info("Session service validate called path={} sessionToken={}", validatePath, maskToken(normalizedToken))

        return webClient.post()
            .uri(validatePath)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("sessionToken" to normalizedToken))
            .retrieve()
            .bodyToMono(JsonNode::class.java)
            .timeout(Duration.ofSeconds(properties.timeoutSeconds))
            .map { body -> parseValidatedSession(body, normalizedToken) }
            .doOnSuccess { validated ->
                log.info(
                    "Session service validate accepted userId={} sessionToken={}",
                    validated.userId,
                    maskToken(normalizedToken),
                )
            }
            .doOnError { error ->
                log.error(
                    "Session service validate failed sessionToken={} reason={}",
                    maskToken(normalizedToken),
                    error.message ?: error.javaClass.simpleName,
                    error,
                )
            }
            .onErrorMap(WebClientResponseException::class.java, ::toGatewayError)
    }

    fun publishEvent(
        sessionToken: String,
        event: String,
        payload: Map<String, Any?>,
    ): Mono<Void> {
        if (!properties.enabled) {
            return Mono.empty()
        }

        val normalizedToken = sessionToken.trim()
        if (normalizedToken.isBlank()) {
            return Mono.empty()
        }

        val eventsPath = properties.eventsPath.trim().ifBlank { "/api/v1/sessions/events" }
        log.info(
            "Session service event publish called path={} event={} sessionToken={} payload={}",
            eventsPath,
            event,
            maskToken(normalizedToken),
            payload,
        )

        return webClient.post()
            .uri(eventsPath)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                mapOf(
                    "sessionToken" to normalizedToken,
                    "event" to event,
                    "payload" to payload,
                ),
            )
            .retrieve()
            .bodyToMono(JsonNode::class.java)
            .timeout(Duration.ofSeconds(properties.timeoutSeconds))
            .doOnSuccess {
                log.info(
                    "Session service event publish accepted event={} sessionToken={}",
                    event,
                    maskToken(normalizedToken),
                )
            }
            .doOnError { error ->
                log.error(
                    "Session service event publish failed event={} sessionToken={} payload={} reason={}",
                    event,
                    maskToken(normalizedToken),
                    payload,
                    error.message ?: error.javaClass.simpleName,
                    error,
                )
            }
            .onErrorResume { Mono.empty() }
            .then()
    }

    private fun parseValidatedSession(body: JsonNode, sessionToken: String): ValidatedSession {
        if (body.has("success") && body.path("success").isBoolean && !body.path("success").asBoolean()) {
            throw DomainException(
                HttpStatus.UNAUTHORIZED,
                body.path("message").asText("Session validation failed."),
            )
        }

        val data = body.path("data")
            .takeUnless { it.isMissingNode || it.isNull }
            ?: body

        val userId = firstText(data, "userId", "user_id", "id")
        if (userId.isBlank()) {
            throw DomainException(HttpStatus.BAD_GATEWAY, "Session validate response did not include userId.")
        }

        val displayName = firstText(data, "displayName", "display_name", "username", "name")
            .ifBlank { "Player ${userId.takeLast(4).uppercase()}" }

        return ValidatedSession(
            sessionToken = firstText(data, "sessionToken", "session_token").ifBlank { sessionToken },
            userId = userId,
            displayName = displayName,
            balance = firstDecimal(data, "balance", "availableBalance", "available_balance"),
            currency = firstText(data, "currency").ifBlank { "INR" },
            operatorUserId = firstText(data, "operatorUserId", "operator_user_id").ifBlank { userId },
            operatorId = firstText(data, "operatorId", "operator_id").ifBlank { null },
            gameId = firstInt(data, "gameId", "game_id"),
        )
    }

    private fun firstText(node: JsonNode, vararg names: String): String {
        return names.firstNotNullOfOrNull { name ->
            node.path(name).takeIf { !it.isMissingNode && !it.isNull }?.asText()?.trim()
        }.orEmpty()
    }

    private fun firstDecimal(node: JsonNode, vararg names: String): BigDecimal {
        val raw = firstText(node, *names)
        return raw.toBigDecimalOrNull() ?: BigDecimal.ZERO
    }

    private fun firstInt(node: JsonNode, vararg names: String): Int? {
        return names.firstNotNullOfOrNull { name ->
            val value = node.path(name)
            when {
                value.isMissingNode || value.isNull -> null
                value.isInt || value.isLong -> value.asInt()
                else -> value.asText()?.trim()?.toIntOrNull()
            }
        }
    }

    private fun toGatewayError(error: WebClientResponseException): DomainException {
        val message = error.responseBodyAsString.takeIf { it.isNotBlank() }
            ?: "Session service request failed with status ${error.statusCode.value()}."
        val status = when (error.statusCode.value()) {
            401, 403 -> HttpStatus.UNAUTHORIZED
            404 -> HttpStatus.NOT_FOUND
            else -> HttpStatus.BAD_GATEWAY
        }
        return DomainException(status, message)
    }

    private fun maskToken(token: String): String {
        if (token.length <= 8) {
            return "***"
        }
        return "${token.take(4)}...${token.takeLast(4)}"
    }
}

@Service
class SessionBindingService(
    private val sessionServiceClient: SessionServiceClient,
    private val bindingRepository: ExternalSessionBindingRepository,
    private val clock: Clock,
) {
    fun isEnabled(): Boolean = sessionServiceClient.isEnabled()

    fun validateAndBind(sessionToken: String, fallbackGameId: Int? = null): Mono<ValidatedSession> {
        return sessionServiceClient.validateSession(sessionToken)
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

fun humanSessionTokens(room: RoomDocument): List<String> =
    room.seats
        .filter { seat -> !seat.isBot && !seat.isAbandoned }
        .mapNotNull { seat -> seat.sessionToken?.trim()?.takeIf { token -> token.isNotEmpty() } }

    private val sessionServiceClient: SessionServiceClient,
) {
    fun publishRoundStarted(
        matchId: String,
        entryFee: Long,
        roomId: String,
        roomCode: String,
        sessionTokens: List<String>,
    ): Mono<Void> {
        if (!sessionServiceClient.isEnabled() || sessionTokens.isEmpty()) {
            return Mono.empty()
        }

        val payload = mapOf(
            "roundId" to matchId,
            "betAmount" to entryFee,
            "roomId" to roomId,
            "roomCode" to roomCode,
        )

        return Flux.fromIterable(sessionTokens.distinct())
            .flatMap { token ->
                sessionServiceClient.publishEvent(
                    sessionToken = token,
                    event = SessionGameEvents.ROUND_STARTED,
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
        if (!sessionServiceClient.isEnabled() || sessionTokens.isEmpty()) {
            return Mono.empty()
        }

        val payload = mapOf(
            "roundId" to matchId,
            "betAmount" to entryFee,
            "potAmount" to potAmount,
            "winnerUserId" to winnerUserId,
            "winnerDisplayName" to winnerDisplayName,
            "roomId" to roomId,
            "roomCode" to roomCode,
        )

        return Flux.fromIterable(sessionTokens.distinct())
            .flatMap { token ->
                sessionServiceClient.publishEvent(
                    sessionToken = token,
                    event = SessionGameEvents.ROUND_FINISHED,
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
        if (!sessionServiceClient.isEnabled() || sessionTokens.isEmpty()) {
            return Mono.empty()
        }

        val payload = mapOf(
            "roundId" to roundId,
            "reason" to reason,
        )

        return Flux.fromIterable(sessionTokens.distinct())
            .flatMap { token ->
                sessionServiceClient.publishEvent(
                    sessionToken = token,
                    event = SessionGameEvents.ROUND_CANCELLED,
                    payload = payload,
                )
            }
            .then()
    }
}
