package com.craft.ludo.operator

import com.craft.ludo.shared.api.DomainException
import com.craft.ludo.shared.config.AppProperties
import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Mono
import reactor.util.retry.Retry
import java.math.BigDecimal
import java.time.Duration
import java.util.concurrent.TimeoutException

object OperationsWalletPaths {
    const val BALANCE = "/api/v1/wallet/balance"
    const val CREDIT_TRANSFER = "/api/v1/wallet/credit-transfer"
}

object OperationsGameTypes {
    const val SPIN = "spin"
    const val FREESPIN = "freespin"
    const val BONUS = "bonus"
    const val GAMBLE = "gamble"
    const val GAMBLE_COLLECT = "gamble_collect"
    const val REFUND = "refund"

    fun ttypeFor(gameType: String): Int =
        if (gameType.equals(REFUND, ignoreCase = true)) 2 else 1
}

private val RETRYABLE_ERROR_CODES = setOf("ERR-0020", "ERR-0021")
private val SESSION_EXPIRED_ERROR_CODES = setOf("ERR-0011", "ERR-0012", "ERR-0013")

/**
 * Server-to-server client for the Operations Wallet API:
 * - POST /api/v1/wallet/balance (opType 2051)
 * - POST /api/v1/wallet/credit-transfer (opType 2052)
 *
 * Implements the Game Developer Guide failure & recovery playbook:
 * - Retries network timeout / 502 / ERR-0020 / ERR-0021 with the exact same `tranid`.
 * - Sends `game_type: "refund"`, `ttype: 2`, `bet: amount`, `win: 0` with a unique refund `tranid`.
 * - Sends `partner_code` and `user_id` alongside `token` so late wins settle after session close.
 * - Surfaces `ERR-0011` / `ERR-0012` / `ERR-0013` as HTTP 401 so clients halt wagering and exit to `close_url`.
 * - Does NOT retry HTTP 400 / business refusals (e.g. insufficient balance).
 */
@Service
class OperationsWalletClient(
    webClientBuilder: WebClient.Builder,
    appProperties: AppProperties,
) {
    private val log = LoggerFactory.getLogger(OperationsWalletClient::class.java)
    private val properties = appProperties.operationsWallet
    private val baseUrl = properties.baseUrl.trimEnd('/')
    private val internalKey = properties.internalKey.trim()
    private val timeout = Duration.ofSeconds(properties.timeoutSeconds.coerceAtLeast(1L))
    private val webClient = webClientBuilder
        .baseUrl(baseUrl)
        .build()

    private class RetryableOperationsException(message: String, cause: Throwable? = null) :
        RuntimeException(message, cause)

    fun isEnabled(): Boolean = properties.enabled || internalKey.isNotBlank()

    /**
     * Fetches initial or refreshed player balance (triggers opType 2051).
     */
    fun getBalance(token: String, partnerCode: String): Mono<BigDecimal> {
        requireConfigured()
        val normalizedToken = token.trim()
        val normalizedPartner = partnerCode.trim().uppercase()
        if (normalizedToken.isBlank() || normalizedPartner.isBlank()) {
            return Mono.error(
                DomainException(HttpStatus.BAD_REQUEST, "token and partner_code are required for balance fetch."),
            )
        }

        val body = mapOf(
            "token" to normalizedToken,
            "partner_code" to normalizedPartner,
        )

        log.info(
            "Operations wallet balance (2051) requested partnerCode={} token={} url={}{}",
            normalizedPartner,
            maskToken(normalizedToken),
            baseUrl,
            OperationsWalletPaths.BALANCE,
        )

        return postInternal(OperationsWalletPaths.BALANCE, body, tranid = null)
            .map(::extractBalance)
            .doOnSuccess { balance ->
                log.info(
                    "Operations wallet balance (2051) accepted partnerCode={} token={} balance={}",
                    normalizedPartner,
                    maskToken(normalizedToken),
                    balance,
                )
            }
    }

    /**
     * Executes a bet, win settlement, or refund transfer (triggers opType 2052).
     * Retries transient failures (502 / timeout / ERR-0020 / ERR-0021) using the exact same `tranid`.
     */
    fun creditTransfer(
        token: String,
        partnerCode: String,
        gid: Any,
        bet: Long,
        win: Long,
        gameType: String = OperationsGameTypes.SPIN,
        tranid: String,
        ttype: Int = OperationsGameTypes.ttypeFor(gameType),
        userId: String? = null,
        roundId: String? = null,
    ): Mono<JsonNode> {
        requireConfigured()
        val normalizedToken = token.trim()
        val normalizedPartner = partnerCode.trim().uppercase()
        val normalizedGid: Any = when (gid) {
            is Number -> gid.toInt()
            is String -> gid.trim().toIntOrNull() ?: gid.trim()
            else -> gid.toString()
        }

        val body = buildMap<String, Any> {
            if (normalizedToken.isNotBlank()) {
                put("token", normalizedToken)
            }
            if (normalizedPartner.isNotBlank()) {
                put("partner_code", normalizedPartner)
            }
            userId?.trim()?.takeIf { it.isNotEmpty() }?.let { put("user_id", it) }
            put("gid", normalizedGid)
            put("bet", bet)
            put("win", win)
            put("game_type", gameType)
            put("ttype", ttype)
            put("tranid", tranid)
            roundId?.trim()?.takeIf { it.isNotEmpty() }?.let { put("round_id", it) }
        }

        log.info(
            "Operations wallet credit-transfer (2052) requested partnerCode={} gid={} bet={} win={} gameType={} ttype={} tranid={} roundId={} token={}",
            normalizedPartner,
            normalizedGid,
            bet,
            win,
            gameType,
            ttype,
            tranid,
            roundId,
            maskToken(normalizedToken),
        )

        return postInternal(OperationsWalletPaths.CREDIT_TRANSFER, body, tranid = tranid)
            .doOnSuccess { responseBody ->
                log.info(
                    "Operations wallet credit-transfer (2052) accepted partnerCode={} gid={} bet={} win={} gameType={} ttype={} tranid={} response={}",
                    normalizedPartner,
                    normalizedGid,
                    bet,
                    win,
                    gameType,
                    ttype,
                    tranid,
                    responseBody,
                )
            }
    }

    private fun postInternal(path: String, body: Map<String, Any>, tranid: String?): Mono<JsonNode> {
        val attempt = Mono.defer {
            webClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Internal-Key", internalKey)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode::class.java)
                .timeout(timeout)
                .map(::inspectBodyOrThrow)
                .onErrorMap(WebClientResponseException::class.java) { ex ->
                    mapHttpError(path, tranid, ex)
                }
                .onErrorMap(WebClientRequestException::class.java) { ex ->
                    RetryableOperationsException(
                        "Unable to reach operations wallet service: ${ex.message}",
                        ex,
                    )
                }
                .onErrorMap(TimeoutException::class.java) { ex ->
                    RetryableOperationsException("Operations wallet service timed out.", ex)
                }
        }

        return attempt
            .retryWhen(
                Retry.backoff(2, Duration.ofMillis(500))
                    .filter { it is RetryableOperationsException }
                    .doBeforeRetry { signal ->
                        log.warn(
                            "Retrying operations wallet call with identical tranid path={} tranid={} attempt={} reason={}",
                            path,
                            tranid,
                            signal.totalRetries() + 1,
                            signal.failure().message,
                        )
                    }
                    .onRetryExhaustedThrow { _, signal ->
                        DomainException(
                            HttpStatus.BAD_GATEWAY,
                            signal.failure().message ?: "Operations wallet service unavailable after retries.",
                        )
                    },
            )
    }

    private fun inspectBodyOrThrow(body: JsonNode): JsonNode {
        val errorCode = extractErrorCode(body)
        if (errorCode != null && errorCode in SESSION_EXPIRED_ERROR_CODES) {
            val msg = extractErrorMessage(body, "Session invalid or expired ($errorCode). Please return to the lobby.")
            throw DomainException(HttpStatus.UNAUTHORIZED, "$msg ($errorCode)")
        }
        if (errorCode != null && errorCode in RETRYABLE_ERROR_CODES) {
            val msg = extractErrorMessage(body, "Transient operations error ($errorCode).")
            throw RetryableOperationsException("$msg ($errorCode)")
        }

        val failedSuccess = body.has("success") && body.path("success").isBoolean && !body.path("success").asBoolean()
        val failedStatus = body.has("status") && body.path("status").isBoolean && !body.path("status").asBoolean()
        if (failedSuccess || failedStatus) {
            val msg = extractErrorMessage(body, "Operations wallet request refused.")
            val status = if (msg.contains("insufficient", ignoreCase = true)) {
                HttpStatus.PAYMENT_REQUIRED
            } else {
                HttpStatus.BAD_REQUEST
            }
            throw DomainException(status, msg)
        }
        return body
    }

    private fun mapHttpError(path: String, tranid: String?, ex: WebClientResponseException): Throwable {
        val rawBody = ex.responseBodyAsString
        val statusCode = ex.statusCode.value()
        log.error(
            "Operations wallet HTTP error path={} tranid={} status={} body={}",
            path,
            tranid,
            statusCode,
            rawBody,
        )

        val matchedExpired = SESSION_EXPIRED_ERROR_CODES.firstOrNull { rawBody.contains(it, ignoreCase = true) }
        if (matchedExpired != null || statusCode == 401) {
            return DomainException(
                HttpStatus.UNAUTHORIZED,
                "Session invalid or expired${matchedExpired?.let { " ($it)" } ?: ""}. Please return to the lobby.",
            )
        }

        val matchedRetryable = RETRYABLE_ERROR_CODES.firstOrNull { rawBody.contains(it, ignoreCase = true) }
        if (matchedRetryable != null || statusCode == 502 || statusCode == 503 || statusCode == 504) {
            return RetryableOperationsException(
                "Operations wallet transient failure (HTTP $statusCode${matchedRetryable?.let { ", $it" } ?: ""}).",
                ex,
            )
        }

        if (statusCode == 402 || rawBody.contains("insufficient", ignoreCase = true)) {
            return DomainException(
                HttpStatus.PAYMENT_REQUIRED,
                rawBody.ifBlank { "Insufficient wallet balance." },
            )
        }

        if (statusCode == 400) {
            return DomainException(
                HttpStatus.BAD_REQUEST,
                rawBody.ifBlank { "Operator refused wallet transaction." },
            )
        }

        return DomainException(
            HttpStatus.BAD_GATEWAY,
            rawBody.ifBlank { "Operations wallet call failed ($statusCode)." },
        )
    }

    private fun extractErrorCode(body: JsonNode): String? {
        val candidates = listOf(
            body.path("code"),
            body.path("error_code"),
            body.path("errorCode"),
            body.path("error").path("code"),
            body.path("data").path("code"),
        )
        for (node in candidates) {
            if (node.isTextual) {
                val value = node.asText().trim().uppercase()
                if (value.isNotEmpty()) return value
            }
        }
        return null
    }

    private fun extractErrorMessage(body: JsonNode, fallback: String): String {
        val candidates = listOf(
            body.path("message"),
            body.path("msg"),
            body.path("error").path("message"),
            body.path("error"),
        )
        for (node in candidates) {
            if (node.isTextual && node.asText().isNotBlank()) {
                return node.asText().trim()
            }
        }
        return fallback
    }

    private fun extractBalance(body: JsonNode): BigDecimal {
        val candidates = listOf(
            body.path("balance"),
            body.path("availableBalance"),
            body.path("amount"),
            body.path("message"),
            body.path("data").path("balance"),
            body.path("data").path("availableBalance"),
            body.path("data").path("amount"),
            body.path("data").path("message"),
            body.path("wallet").path("balance"),
        )
        for (node in candidates) {
            if (node.isNumber) {
                return node.decimalValue()
            }
            if (node.isTextual) {
                node.asText().trim().toBigDecimalOrNull()?.let { return it }
            }
        }
        return BigDecimal.ZERO
    }

    private fun requireConfigured() {
        if (internalKey.isBlank()) {
            throw DomainException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Operations wallet X-Internal-Key is not configured. Set OPERATIONS_INTERNAL_KEY or INTERNAL_SERVICE_KEY.",
            )
        }
    }

    private fun maskToken(token: String): String =
        if (token.length <= 8) "***" else "${token.take(4)}...${token.takeLast(4)}"
}
