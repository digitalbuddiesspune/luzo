package com.craft.ludo.operator

import com.craft.ludo.identity.IdentityService
import com.craft.ludo.provider.ProviderGameSdkClient
import com.craft.ludo.shared.api.DomainException
import com.craft.ludo.shared.config.AppProperties
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant

data class OperatorDebitRequest(
    val gameUserId: String,
    val txnId: String,
    val amount: BigDecimal,
    val description: String,
    val ip: String,
    val gameId: Int,
    val userId: String,
    val operatorId: String,
    val token: String,
    val betId: String,
    val roundId: String? = null,
    val tableId: String? = null,
    val txnType: Int = 0,
)

/**
 * Cashout payload published to RabbitMQ — field names/types match the operator integration guide.
 * Amount and game_id are strings (e.g. "700.00", "2").
 */
data class OperatorCreditQueueMessage(
    val txn_id: String,
    val txn_ref_id: String,
    val txn_type: Int = 1,
    val amount: String,
    val user_id: String,
    val game_id: String,
    val description: String,
    val ip: String,
    val operatorId: String,
    val token: String,
    /** Match or room id included in HTTP credit requests. */
    val round_id: String? = null,
)

data class OperatorGatewayLogEvent(
    val id: String,
    val eventType: String,
    val action: String,
    val gameUserId: String,
    val userId: String,
    val operatorId: String,
    val txnId: String,
    val amount: BigDecimal,
    val description: String,
    val target: String,
    val createdAt: Instant,
    val txnRefId: String? = null,
    val ip: String? = null,
    val gameId: Int? = null,
    val exchange: String? = null,
    val routingKey: String? = null,
    val txnType: Int? = null,
)

data class OperatorGatewayStreamStatus(
    val status: String,
    val userId: String,
    val operatorUserId: String,
    val createdAt: Instant,
)

@Service
class OperatorGatewayLogStream {
    private val log = LoggerFactory.getLogger(OperatorGatewayLogStream::class.java)
    private val sink = Sinks.many().replay().limit<OperatorGatewayLogEvent>(100)

    fun publish(event: OperatorGatewayLogEvent) {
        val result = sink.tryEmitNext(event)
        log.info(
            "Operator gateway browser log published result={} eventType={} gameUserId={} operatorUserId={} txnId={}",
            result,
            event.eventType,
            event.gameUserId,
            event.userId,
            event.txnId,
        )
    }

    fun stream(): Flux<OperatorGatewayLogEvent> = sink.asFlux()
}

@Service
class OperatorGatewayClient(
    private val webClientBuilder: WebClient.Builder,
    private val operatorGatewayLogStream: OperatorGatewayLogStream,
    private val providerGameSdkClient: ProviderGameSdkClient,
    @Suppress("unused") private val rabbitTemplate: RabbitTemplate,
    appProperties: AppProperties,
) {
    private val log = LoggerFactory.getLogger(OperatorGatewayClient::class.java)
    private val operatorProperties = appProperties.operator

    init {
        require(operatorProperties.gameId > 0) { "app.operator.game-id must be positive." }
    }

    fun debit(request: OperatorDebitRequest): Mono<String> {
        val debitAmount = request.amount
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()

        if (providerGameSdkClient.hasWalletAccess()) {
            return debitViaProviderSdk(request, debitAmount)
        }

        val debitUrl = operatorProperties.debitUrl.trim()
        if (debitUrl.isBlank()) {
            return Mono.error(
                DomainException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Wallet debit is not configured. Set GAME_SERVER_API_KEY or APP_WALLET_DEBIT_URL.",
                ),
            )
        }

        val gameName = operatorProperties.creditGameName.trim().ifBlank { "Ludo" }

        operatorGatewayLogStream.publish(
            OperatorGatewayLogEvent(
                id = "operator_debit:${request.txnId}",
                eventType = "operator_debit_api_called",
                action = "Wallet debit API called",
                gameUserId = request.gameUserId,
                userId = request.userId,
                operatorId = request.operatorId,
                txnId = request.txnId,
                amount = request.amount,
                description = request.description,
                target = debitUrl,
                createdAt = Instant.now(),
                ip = request.ip,
                gameId = request.gameId,
                txnType = request.txnType,
            ),
        )
        log.info(
            "Wallet debit api called gameUserId={} userId={} operatorId={} txnId={} betId={} amount={} gameName={} url={}",
            request.gameUserId,
            request.userId,
            request.operatorId,
            request.txnId,
            request.betId,
            debitAmount,
            gameName,
            debitUrl,
        )

        return webClientBuilder.build()
            .post()
            .uri(debitUrl)
            .header("Authorization", "Bearer ${request.token}")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                mapOf(
                    "userId" to request.userId,
                    "gameName" to gameName,
                    "amount" to debitAmount,
                    "description" to request.description,
                ),
            )
            .retrieve()
            .bodyToMono(JsonNode::class.java)
            .timeout(Duration.ofSeconds(5))
            .doOnError { error ->
                log.error(
                    "Wallet debit api failed gameUserId={} userId={} operatorId={} txnId={} betId={} amount={} gameName={} url={} reason={}",
                    request.gameUserId,
                    request.userId,
                    request.operatorId,
                    request.txnId,
                    request.betId,
                    debitAmount,
                    gameName,
                    debitUrl,
                    error.message ?: error.javaClass.simpleName,
                    error,
                )
            }
            .map { body ->
                if (body.has("status") && body.path("status").isBoolean && !body.path("status").asBoolean()) {
                    throw DomainException(HttpStatus.BAD_GATEWAY, body.path("msg").asText("Wallet debit failed."))
                }
                if (body.has("success") && body.path("success").isBoolean && !body.path("success").asBoolean()) {
                    throw DomainException(
                        HttpStatus.BAD_GATEWAY,
                        body.path("message").asText("Wallet debit failed."),
                    )
                }
                log.info(
                    "Wallet debit api accepted gameUserId={} userId={} operatorId={} txnId={} betId={} amount={} description={}",
                    request.gameUserId,
                    request.userId,
                    request.operatorId,
                    request.txnId,
                    request.betId,
                    debitAmount,
                    request.description,
                )
                operatorGatewayLogStream.publish(
                    OperatorGatewayLogEvent(
                        id = "operator_debit_accepted:${request.txnId}",
                        eventType = "operator_debit_api_accepted",
                        action = "Wallet debit API accepted",
                        gameUserId = request.gameUserId,
                        userId = request.userId,
                        operatorId = request.operatorId,
                        txnId = request.txnId,
                        amount = request.amount,
                        description = request.description,
                        target = debitUrl,
                        createdAt = Instant.now(),
                        ip = request.ip,
                        gameId = request.gameId,
                        txnType = request.txnType,
                    ),
                )
                request.txnId
            }
            .onErrorMap(WebClientResponseException::class.java, ::toGatewayError)
    }

    fun enqueueCredit(message: OperatorCreditQueueMessage): Mono<Void> {
        if (providerGameSdkClient.hasWalletAccess()) {
            return creditViaProviderSdk(message)
        }

        if (message.txn_ref_id.startsWith("roomfee:")) {
            log.warn(
                "Blocked wallet credit for legacy/unconfirmed debit reference userId={} txnId={} txnRefId={} amount={}",
                message.user_id,
                message.txn_id,
                message.txn_ref_id,
                message.amount,
            )
            operatorGatewayLogStream.publish(
                OperatorGatewayLogEvent(
                    id = "operator_credit_blocked:${message.txn_id}",
                    eventType = "operator_credit_blocked_legacy_debit_ref",
                    action = "Wallet credit blocked for legacy debit reference",
                    gameUserId = message.user_id,
                    userId = message.user_id,
                    operatorId = message.operatorId,
                    txnId = message.txn_id,
                    txnRefId = message.txn_ref_id,
                    amount = message.amount.toBigDecimalOrNull() ?: BigDecimal.ZERO,
                    description = message.description,
                    target = resolveCreditUrl().ifBlank { "credit-api-unconfigured" },
                    createdAt = Instant.now(),
                    ip = message.ip,
                    gameId = message.game_id.toIntOrNull(),
                ),
            )
            return Mono.empty()
        }

        val creditUrl = resolveCreditUrl()
        if (creditUrl.isBlank()) {
            log.error(
                "Wallet credit skipped: APP_WALLET_CREDIT_URL (or APP_OPERATOR_CREDIT_URL) is not configured userId={} txnId={} amount={}",
                message.user_id,
                message.txn_id,
                message.amount,
            )
            return Mono.error(
                DomainException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Wallet credit URL is not configured. Set APP_WALLET_CREDIT_URL.",
                ),
            )
        }

        return creditViaHttp(creditUrl, message)
    }

    private fun creditViaHttp(creditUrl: String, message: OperatorCreditQueueMessage): Mono<Void> {
        val amountDecimal = message.amount.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val creditAmount = amountDecimal
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
        val gameName = operatorProperties.creditGameName.trim().ifBlank { "Ludo" }

        operatorGatewayLogStream.publish(
            OperatorGatewayLogEvent(
                id = "operator_credit:${message.txn_id}",
                eventType = "operator_credit_api_called",
                action = "Wallet credit API called",
                gameUserId = message.user_id,
                userId = message.user_id,
                operatorId = message.operatorId,
                txnId = message.txn_id,
                txnRefId = message.txn_ref_id,
                amount = amountDecimal,
                description = message.description,
                target = creditUrl,
                createdAt = Instant.now(),
                ip = message.ip,
                gameId = message.game_id.toIntOrNull(),
                txnType = message.txn_type,
            ),
        )
        log.info(
            "Wallet credit api called userId={} operatorId={} txnId={} amount={} gameName={} url={}",
            message.user_id,
            message.operatorId,
            message.txn_id,
            creditAmount,
            gameName,
            creditUrl,
        )

        return webClientBuilder.build()
            .post()
            .uri(creditUrl)
            .header("Authorization", "Bearer ${message.token}")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                mapOf(
                    "userId" to message.user_id,
                    "gameName" to gameName,
                    "amount" to creditAmount,
                    "description" to message.description,
                ),
            )
            .retrieve()
            .bodyToMono(JsonNode::class.java)
            .timeout(Duration.ofSeconds(5))
            .doOnError { error ->
                log.error(
                    "Wallet credit api failed userId={} operatorId={} txnId={} amount={} gameName={} url={} reason={}",
                    message.user_id,
                    message.operatorId,
                    message.txn_id,
                    creditAmount,
                    gameName,
                    creditUrl,
                    error.message ?: error.javaClass.simpleName,
                    error,
                )
            }
            .map { body ->
                if (body.has("status") && body.path("status").isBoolean && !body.path("status").asBoolean()) {
                    throw DomainException(HttpStatus.BAD_GATEWAY, body.path("msg").asText("Wallet credit failed."))
                }
                if (body.has("success") && body.path("success").isBoolean && !body.path("success").asBoolean()) {
                    throw DomainException(
                        HttpStatus.BAD_GATEWAY,
                        body.path("message").asText("Wallet credit failed."),
                    )
                }
                log.info(
                    "Wallet credit api accepted userId={} operatorId={} txnId={} amount={} gameName={}",
                    message.user_id,
                    message.operatorId,
                    message.txn_id,
                    creditAmount,
                    gameName,
                )
                operatorGatewayLogStream.publish(
                    OperatorGatewayLogEvent(
                        id = "operator_credit_accepted:${message.txn_id}",
                        eventType = "operator_credit_api_accepted",
                        action = "Wallet credit API accepted",
                        gameUserId = message.user_id,
                        userId = message.user_id,
                        operatorId = message.operatorId,
                        txnId = message.txn_id,
                        txnRefId = message.txn_ref_id,
                        amount = amountDecimal,
                        description = message.description,
                        target = creditUrl,
                        createdAt = Instant.now(),
                        ip = message.ip,
                        gameId = message.game_id.toIntOrNull(),
                        txnType = message.txn_type,
                    ),
                )
            }
            .onErrorMap(WebClientResponseException::class.java, ::toGatewayError)
            .then()
    }

    fun publishExistingDebitReservation(
        gameUserId: String,
        operatorUserId: String,
        operatorId: String,
        txnId: String,
        amount: BigDecimal,
        description: String,
        ip: String?,
        gameId: Int?,
    ) {
        operatorGatewayLogStream.publish(
            OperatorGatewayLogEvent(
                id = "operator_debit_reused:$txnId",
                eventType = "operator_debit_reused_existing_reservation",
                action = "Existing wallet debit reservation reused",
                gameUserId = gameUserId,
                userId = operatorUserId,
                operatorId = operatorId,
                txnId = txnId,
                amount = amount,
                description = description,
                target = "wallet_transactions",
                createdAt = Instant.now(),
                ip = ip,
                gameId = gameId,
            ),
        )
    }

    fun gameId(): Int = operatorProperties.gameId

    private fun debitViaProviderSdk(request: OperatorDebitRequest, debitAmount: Long): Mono<String> {
        val target = "provider-sdk:/adapters/${request.operatorId}/debit"
        operatorGatewayLogStream.publish(
            OperatorGatewayLogEvent(
                id = "operator_debit:${request.txnId}",
                eventType = "operator_debit_api_called",
                action = "Provider SDK debit called",
                gameUserId = request.gameUserId,
                userId = request.userId,
                operatorId = request.operatorId,
                txnId = request.txnId,
                amount = request.amount,
                description = request.description,
                target = target,
                createdAt = Instant.now(),
                ip = request.ip,
                gameId = request.gameId,
                txnType = request.txnType,
            ),
        )

        return providerGameSdkClient.debit(
            operatorId = request.operatorId,
            sessionToken = request.token,
            amount = debitAmount,
            transactionId = request.txnId,
            roundId = request.roundId ?: request.txnId,
            tableId = request.tableId,
        )
            .map {
                operatorGatewayLogStream.publish(
                    OperatorGatewayLogEvent(
                        id = "operator_debit_accepted:${request.txnId}",
                        eventType = "operator_debit_api_accepted",
                        action = "Provider SDK debit accepted",
                        gameUserId = request.gameUserId,
                        userId = request.userId,
                        operatorId = request.operatorId,
                        txnId = request.txnId,
                        amount = request.amount,
                        description = request.description,
                        target = target,
                        createdAt = Instant.now(),
                        ip = request.ip,
                        gameId = request.gameId,
                        txnType = request.txnType,
                    ),
                )
                request.txnId
            }
    }

    private fun creditViaProviderSdk(message: OperatorCreditQueueMessage): Mono<Void> {
        if (message.txn_ref_id.startsWith("roomfee:")) {
            log.warn(
                "Blocked provider SDK credit for legacy debit reference userId={} txnId={} txnRefId={}",
                message.user_id,
                message.txn_id,
                message.txn_ref_id,
            )
            return Mono.empty()
        }

        val amountDecimal = message.amount.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val creditAmount = amountDecimal
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
        val target = "provider-sdk:/adapters/${message.operatorId}/credit"

        operatorGatewayLogStream.publish(
            OperatorGatewayLogEvent(
                id = "operator_credit:${message.txn_id}",
                eventType = "operator_credit_api_called",
                action = "Provider SDK credit called",
                gameUserId = message.user_id,
                userId = message.user_id,
                operatorId = message.operatorId,
                txnId = message.txn_id,
                txnRefId = message.txn_ref_id,
                amount = amountDecimal,
                description = message.description,
                target = target,
                createdAt = Instant.now(),
                ip = message.ip,
                gameId = message.game_id.toIntOrNull(),
                txnType = message.txn_type,
            ),
        )

        return providerGameSdkClient.credit(
            operatorId = message.operatorId,
            sessionToken = message.token,
            amount = creditAmount,
            transactionId = message.txn_id,
            roundId = message.round_id,
        )
            .map {
                operatorGatewayLogStream.publish(
                    OperatorGatewayLogEvent(
                        id = "operator_credit_accepted:${message.txn_id}",
                        eventType = "operator_credit_api_accepted",
                        action = "Provider SDK credit accepted",
                        gameUserId = message.user_id,
                        userId = message.user_id,
                        operatorId = message.operatorId,
                        txnId = message.txn_id,
                        txnRefId = message.txn_ref_id,
                        amount = amountDecimal,
                        description = message.description,
                        target = target,
                        createdAt = Instant.now(),
                        ip = message.ip,
                        gameId = message.game_id.toIntOrNull(),
                        txnType = message.txn_type,
                    ),
                )
            }
            .then()
    }

    private fun resolveCreditUrl(): String = operatorProperties.creditUrl.trim()

    private fun toGatewayError(error: WebClientResponseException): DomainException {
        val message = error.responseBodyAsString.takeIf { it.isNotBlank() }
            ?: "Wallet gateway request failed with status ${error.statusCode.value()}."
        return DomainException(HttpStatus.BAD_GATEWAY, message)
    }
}

@RestController
@RequestMapping("/api/v1/operator-gateway")
class OperatorGatewayLogController(
    private val operatorGatewayLogStream: OperatorGatewayLogStream,
    private val identityService: IdentityService,
) {
    private val log = LoggerFactory.getLogger(OperatorGatewayLogController::class.java)

    @GetMapping("/logs", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun streamLogs(
        @RequestParam("sessionToken") sessionToken: String,
    ): Flux<ServerSentEvent<Any>> {
        return identityService.findActiveSession(sessionToken)
            .flatMapMany { session ->
                val operatorUserId = session.operatorUserId ?: return@flatMapMany Flux.empty()
                log.info(
                    "Operator gateway browser log stream connected gameUserId={} operatorUserId={}",
                    session.userId,
                    operatorUserId,
                )
                val connectedEvent = ServerSentEvent.builder<Any>(
                    OperatorGatewayStreamStatus(
                        status = "connected",
                        userId = session.userId,
                        operatorUserId = operatorUserId,
                        createdAt = Instant.now(),
                    ),
                )
                    .event("operator_gateway_connected")
                    .build()
                val heartbeat = Flux.interval(Duration.ofSeconds(15))
                    .map {
                        ServerSentEvent.builder<Any>()
                            .comment("operator-gateway-heartbeat")
                            .build()
                    }
                val logEvents = operatorGatewayLogStream.stream()
                    .filter { event -> event.gameUserId == session.userId || event.userId == operatorUserId }
                    .map { event ->
                        ServerSentEvent.builder<Any>(event)
                            .id(event.id)
                            .event("operator_gateway_log")
                            .build()
                    }
                Flux.merge(Flux.just(connectedEvent), heartbeat, logEvents)
            }
    }
}
