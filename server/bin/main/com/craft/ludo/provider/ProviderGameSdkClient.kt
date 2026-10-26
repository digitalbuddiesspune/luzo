package com.craft.ludo.provider

import com.craft.ludo.session.ValidatedSession
import com.craft.ludo.shared.api.DomainException
import com.craft.ludo.shared.config.AppProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.gson.JsonObject
import `in`.oreng.gamesdk.ProviderGameServerSDK
import `in`.oreng.gamesdk.ProviderSDKException
import `in`.oreng.gamesdk.WalletOperationRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.math.BigDecimal
import java.time.Duration

/** Session paths remain on WebClient; wallet adapters use in.oreng:game-sdk. */
object ProviderSdkPaths {
    const val SESSION_VALIDATE = "/sessions/validate"
    const val SESSION_EVENTS = "/sessions/events"
}

@Service
class ProviderGameSdkClient(
    webClientBuilder: WebClient.Builder,
    appProperties: AppProperties,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(ProviderGameSdkClient::class.java)
    private val properties = appProperties.sessionService
    private val apiBase = properties.apiBaseUrl.trimEnd('/')
    private val webClient = webClientBuilder
        .baseUrl(apiBase)
        .build()

    @Volatile
    private var walletSdk: ProviderGameServerSDK? = null

    fun isEnabled(): Boolean = properties.enabled

    fun hasWalletAccess(): Boolean =
        properties.enabled && properties.gameServerApiKey.isNotBlank()

    fun validateSession(sessionToken: String): Mono<ValidatedSession> {
        if (!properties.enabled) {
            return Mono.error(
                DomainException(HttpStatus.SERVICE_UNAVAILABLE, "Provider session service is disabled."),
            )
        }

        val normalizedToken = sessionToken.trim()
        if (normalizedToken.isBlank()) {
            return Mono.error(DomainException(HttpStatus.BAD_REQUEST, "sessionToken is required."))
        }

        log.info(
            "Provider SDK validate sessionToken={} apiBase={}",
            maskToken(normalizedToken),
            apiBase,
        )

        return postJson(
            path = ProviderSdkPaths.SESSION_VALIDATE,
            body = mapOf("sessionToken" to normalizedToken),
        )
            .map { body -> parseValidatedSession(body, normalizedToken) }
            .flatMap { validated -> enrichWithPlatformBalance(validated, normalizedToken) }
            .timeout(Duration.ofSeconds(properties.timeoutSeconds))
            .doOnSuccess { validated ->
                log.info(
                    "Provider SDK validate accepted userId={} operatorId={} sessionToken={}",
                    validated.userId,
                    validated.operatorId,
                    maskToken(normalizedToken),
                )
            }
            .onErrorMap(WebClientResponseException::class.java, ::toGatewayError)
    }

    fun publishEvent(
        sessionToken: String,
        event: String,
        roundId: String? = null,
        tableId: String? = null,
        payload: Map<String, Any?> = emptyMap(),
    ): Mono<Void> {
        if (!properties.enabled) {
            return Mono.empty()
        }

        val normalizedToken = sessionToken.trim()
        if (normalizedToken.isBlank()) {
            return Mono.empty()
        }

        val body = buildMap<String, Any?> {
            put("sessionToken", normalizedToken)
            put("event", event)
            roundId?.takeIf { it.isNotBlank() }?.let { put("roundId", it) }
            tableId?.takeIf { it.isNotBlank() }?.let { put("tableId", it) }
            if (payload.isNotEmpty()) {
                put("payload", payload)
            }
        }

        log.info(
            "Provider SDK event publish event={} roundId={} tableId={} sessionToken={}",
            event,
            roundId,
            tableId,
            maskToken(normalizedToken),
        )

        return postJson(
            path = ProviderSdkPaths.SESSION_EVENTS,
            body = body,
        )
            .timeout(Duration.ofSeconds(properties.timeoutSeconds))
            .doOnSuccess {
                log.info(
                    "Provider SDK event publish accepted event={} sessionToken={}",
                    event,
                    maskToken(normalizedToken),
                )
            }
            .doOnError { error ->
                log.error(
                    "Provider SDK event publish failed event={} sessionToken={} reason={}",
                    event,
                    maskToken(normalizedToken),
                    error.message ?: error.javaClass.simpleName,
                    error,
                )
            }
            .onErrorResume { Mono.empty() }
            .then()
    }

    fun debit(
        sessionToken: String,
        amount: Long,
        transactionId: String,
        roundId: String? = null,
        tableId: String? = null,
        extra: Map<String, Any?> = emptyMap(),
    ): Mono<JsonNode> {
        requireWalletAccess()

        val request = walletOperationRequest(
            sessionToken = sessionToken,
            amount = amount,
            transactionId = transactionId,
            roundId = roundId,
            tableId = tableId,
            extra = extra,
        )

        return walletMono("debit", sessionToken) {
            walletSdk().debit(request)
        }
    }

    fun credit(
        sessionToken: String,
        amount: Long,
        transactionId: String,
        roundId: String? = null,
        tableId: String? = null,
        extra: Map<String, Any?> = emptyMap(),
    ): Mono<JsonNode> {
        requireWalletAccess()

        val request = walletOperationRequest(
            sessionToken = sessionToken,
            amount = amount,
            transactionId = transactionId,
            roundId = roundId,
            tableId = tableId,
            extra = extra,
        )

        return walletMono("credit", sessionToken) {
            walletSdk().credit(request)
        }
    }

    fun getBalance(sessionToken: String): Mono<BigDecimal> {
        requireWalletAccess()

        return walletMono("balance", sessionToken) {
            walletSdk().getBalance(sessionToken)
        }
            .map(::parseWalletBalance)
            .flatMap { balance ->
                if (balance > BigDecimal.ZERO) {
                    Mono.just(balance)
                } else {
                    getPlayerProfileBalance(sessionToken)
                        .map { profileBalance ->
                            if (profileBalance > BigDecimal.ZERO) profileBalance else balance
                        }
                }
            }
    }

    fun enrichWithPlatformBalance(
        validated: ValidatedSession,
        sessionToken: String,
    ): Mono<ValidatedSession> {
        if (!hasWalletAccess()) {
            return Mono.just(validated)
        }

        return getBalance(sessionToken)
            .map { balance -> validated.copy(balance = balance) }
            .onErrorResume { error ->
                log.warn(
                    "Provider SDK balance fetch failed userId={} operatorId={} reason={}",
                    validated.userId,
                    validated.operatorId,
                    error.message ?: error.javaClass.simpleName,
                )
                Mono.just(validated)
            }
    }

    private fun getPlayerProfileBalance(sessionToken: String): Mono<BigDecimal> {
        return walletMono("player-profile", sessionToken) {
            walletSdk().getPlayerProfile(sessionToken)
        }
            .map(::parseWalletBalance)
            .onErrorReturn(BigDecimal.ZERO)
    }

    private fun walletMono(
        operation: String,
        sessionToken: String,
        call: () -> JsonObject,
    ): Mono<JsonNode> {
        log.info(
            "Provider SDK wallet {} sessionToken={} via=in.oreng:game-sdk",
            operation,
            maskToken(sessionToken),
        )

        return Mono.fromCallable {
            toJsonNode(call())
        }
            .subscribeOn(Schedulers.boundedElastic())
            .timeout(Duration.ofSeconds(properties.timeoutSeconds))
            .onErrorMap(ProviderSDKException::class.java, ::toSdkError)
    }

    private fun walletSdk(): ProviderGameServerSDK {
        walletSdk?.let { return it }

        synchronized(this) {
            walletSdk?.let { return it }
            val created = ProviderGameServerSDK(apiBase, properties.gameServerApiKey)
            walletSdk = created
            return created
        }
    }

    private fun walletOperationRequest(
        sessionToken: String,
        amount: Long,
        transactionId: String,
        roundId: String?,
        tableId: String?,
        extra: Map<String, Any?>,
    ): WalletOperationRequest {
        val builder = WalletOperationRequest.builder()
            .sessionToken(sessionToken)
            .amount(amount)
            .transactionId(transactionId)

        roundId?.takeIf { it.isNotBlank() }?.let { builder.roundId(it) }
        tableId?.takeIf { it.isNotBlank() }?.let { builder.tableId(it) }
        extra.forEach { (key, value) ->
            if (value != null) {
                builder.extra(key, value)
            }
        }

        return builder.build()
    }

    private fun postJson(
        path: String,
        body: Map<String, Any?>,
    ): Mono<JsonNode> {
        return webClient.post()
            .uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .retrieve()
            .bodyToMono(JsonNode::class.java)
            .map(::ensureSuccessResponse)
    }

    private fun ensureSuccessResponse(body: JsonNode): JsonNode {
        if (body.has("success") && body.path("success").isBoolean && !body.path("success").asBoolean()) {
            throw DomainException(
                HttpStatus.BAD_GATEWAY,
                body.path("message").asText("Provider API request failed."),
            )
        }
        return body
    }

    private fun parseValidatedSession(body: JsonNode, sessionToken: String): ValidatedSession {
        val session = body.path("session")
            .takeUnless { it.isMissingNode || it.isNull }
            ?: body.path("data").path("session").takeUnless { it.isMissingNode || it.isNull }
            ?: body.path("data").takeUnless { it.isMissingNode || it.isNull }
            ?: body

        val userId = firstText(session, "playerId", "player_id", "userId", "user_id", "id")
        if (userId.isBlank()) {
            throw DomainException(HttpStatus.BAD_GATEWAY, "Provider validate response did not include playerId.")
        }

        val displayName = firstText(session, "playerUsername", "player_username", "displayName", "display_name", "username", "name")
            .ifBlank { "Player ${userId.takeLast(4).uppercase()}" }

        val operatorId = firstText(session, "operatorId", "operator_id").ifBlank { null }
        val gameCode = firstText(session, "gameCode", "game_code", "gameId", "game_id")

        return ValidatedSession(
            sessionToken = firstText(session, "sessionToken", "session_token").ifBlank { sessionToken },
            userId = userId,
            displayName = displayName,
            balance = firstDecimal(session, "balance", "availableBalance", "available_balance"),
            currency = firstText(session, "currency").ifBlank { "INR" },
            operatorUserId = userId,
            operatorId = operatorId,
            gameId = gameCode.toIntOrNull(),
        )
    }

    private fun requireWalletAccess() {
        if (!hasWalletAccess()) {
            throw DomainException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Provider wallet API requires GAME_SERVER_API_KEY and APP_SESSION_SERVICE_ENABLED=true.",
            )
        }
    }

    private fun firstText(node: JsonNode, vararg names: String): String {
        return names.firstNotNullOfOrNull { name ->
            node.path(name).takeIf { !it.isMissingNode && !it.isNull }?.asText()?.trim()
        }.orEmpty()
    }

    private fun parseWalletBalance(body: JsonNode): BigDecimal {
        val payload = unwrapWalletPayload(body)
        return firstDecimal(
            payload,
            "balance",
            "availableBalance",
            "available_balance",
            "walletBalance",
            "wallet_balance",
            "amount",
        )
    }

    private fun unwrapWalletPayload(body: JsonNode): JsonNode {
        val level1 = body.path("data").takeUnless { it.isMissingNode || it.isNull } ?: return body
        val level2 = level1.path("data").takeUnless { it.isMissingNode || it.isNull }
        return level2 ?: level1
    }

    private fun firstDecimal(node: JsonNode, vararg names: String): BigDecimal {
        for (name in names) {
            val value = node.path(name)
            if (value.isMissingNode || value.isNull) {
                continue
            }
            when {
                value.isNumber -> return value.decimalValue()
                else -> value.asText()?.trim()?.toBigDecimalOrNull()?.let { return it }
            }
        }
        return BigDecimal.ZERO
    }

    private fun toJsonNode(payload: JsonObject): JsonNode {
        return objectMapper.readTree(payload.toString())
    }

    private fun toSdkError(error: ProviderSDKException): DomainException {
        val status = when (error.status) {
            401, 403 -> HttpStatus.UNAUTHORIZED
            404 -> HttpStatus.NOT_FOUND
            null -> HttpStatus.BAD_GATEWAY
            else -> HttpStatus.BAD_GATEWAY
        }
        return DomainException(status, error.message ?: "Provider API request failed.")
    }

    private fun toGatewayError(error: WebClientResponseException): DomainException {
        val message = error.responseBodyAsString.takeIf { it.isNotBlank() }
            ?: "Provider API request failed with status ${error.statusCode.value()}."
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
