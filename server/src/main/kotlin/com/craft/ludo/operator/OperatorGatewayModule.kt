package com.craft.ludo.operator

import com.craft.ludo.identity.IdentityService
import com.craft.ludo.shared.api.DomainException
import com.craft.ludo.shared.config.AppProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.amqp.core.MessageBuilder
import org.springframework.amqp.core.MessageProperties
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

data class OperatorLoginResult(
    val token: String,
)

data class OperatorLoginPayload(
    val userId: String,
    val password: String,
)

data class OperatorUserDetail(
    val userId: String,
    val displayName: String,
    val balance: BigDecimal,
    val currency: String,
    val operatorId: String,
)

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
    val txnType: Int = 0,
)

data class OperatorCreditQueueMessage(
    val gameUserId: String,
    val amount: BigDecimal,
    val txn_id: String,
    val txn_ref_id: String,
    val ip: String,
    val game_id: Int,
    val user_id: String,
    val operatorId: String,
    val token: String,
    val description: String,
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
    webClientBuilder: WebClient.Builder,
    private val rabbitTemplate: RabbitTemplate,
    private val objectMapper: ObjectMapper,
    private val operatorGatewayLogStream: OperatorGatewayLogStream,
    appProperties: AppProperties,
) {
    private val log = LoggerFactory.getLogger(OperatorGatewayClient::class.java)
    private val operatorProperties = appProperties.operator
    private val operatorBaseUrl = operatorProperties.baseUrl.trimEnd('/')
    private val serviceBaseUrl = operatorBaseUrl.removeTrailingPathSegment("operator")
    private val webClient = webClientBuilder
        .baseUrl(operatorBaseUrl)
        .build()
    private val serviceWebClient = webClientBuilder
        .baseUrl(serviceBaseUrl)
        .build()

    init {
        require(operatorProperties.baseUrl.isNotBlank()) { "app.operator.base-url must not be blank." }
        require(operatorProperties.gameId > 0) { "app.operator.game-id must be positive." }
        require(operatorProperties.creditExchange.isNotBlank()) { "app.operator.credit-exchange must not be blank." }
        require(operatorProperties.creditQueueName.isNotBlank()) { "app.operator.credit-queue-name must not be blank." }
        require(operatorProperties.creditRoutingKey.isNotBlank()) { "app.operator.credit-routing-key must not be blank." }
    }

    fun login(userId: String, password: String): Mono<OperatorLoginResult> {
        val normalizedUserId = userId.trim()
        if (normalizedUserId.isBlank()) {
            return Mono.error(DomainException(HttpStatus.BAD_REQUEST, "Operator user id is required."))
        }
        if (password.isBlank()) {
            return Mono.error(DomainException(HttpStatus.BAD_REQUEST, "Operator password is required."))
        }

        return webClient.post()
            .uri(operatorProperties.loginPath)
            .contentType(MediaType.APPLICATION_JSON)
            .header("token", "")
            .bodyValue(OperatorLoginPayload(userId = normalizedUserId, password = password))
            .retrieve()
            .bodyToMono(JsonNode::class.java)
            .map { body ->
                if (!body.path("status").asBoolean(false)) {
                    throw DomainException(HttpStatus.BAD_GATEWAY, body.path("msg").asText("Operator login failed."))
                }

                val token = body.path("token").asText("").trim()
                if (token.isBlank()) {
                    throw DomainException(HttpStatus.BAD_GATEWAY, "Operator login response did not include a token.")
                }

                OperatorLoginResult(token = token)
            }
            .onErrorMap(WebClientResponseException::class.java, ::toGatewayError)
    }

    fun fetchUserDetail(token: String): Mono<OperatorUserDetail> {
        val normalizedToken = token.trim()
        if (normalizedToken.isBlank()) {
            return Mono.error(DomainException(HttpStatus.UNAUTHORIZED, "Operator token is required."))
        }

        return webClientFor(operatorProperties.userDetailPath).get()
            .uri(operatorProperties.userDetailPath.withoutDuplicateOperatorPrefix())
            .header("token", normalizedToken)
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .bodyToMono(JsonNode::class.java)
            .map(::parseUserDetail)
            .onErrorMap(WebClientResponseException::class.java, ::toGatewayError)
    }

    fun debit(request: OperatorDebitRequest): Mono<String> {
        operatorGatewayLogStream.publish(
            OperatorGatewayLogEvent(
                id = "operator_debit:${request.txnId}",
                eventType = "operator_debit_api_called",
                action = "Operator debit API called",
                gameUserId = request.gameUserId,
                userId = request.userId,
                operatorId = request.operatorId,
                txnId = request.txnId,
                amount = request.amount,
                description = request.description,
                target = operatorProperties.balancePath,
                createdAt = Instant.now(),
                ip = request.ip,
                gameId = request.gameId,
                txnType = request.txnType,
            ),
        )
        log.info(
            "Operator debit api called gameUserId={} userId={} operatorId={} txnId={} amount={} txnType={} gameId={} path={}",
            request.gameUserId,
            request.userId,
            request.operatorId,
            request.txnId,
            request.amount,
            request.txnType,
            request.gameId,
            operatorProperties.balancePath,
        )

        return webClient.post()
            .uri(operatorProperties.balancePath)
            .header("token", request.token)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                mapOf(
                    "txn_id" to request.txnId,
                    "amount" to request.amount,
                    "description" to request.description,
                    "txn_type" to request.txnType,
                    "ip" to request.ip,
                    "game_id" to request.gameId,
                    "user_id" to request.userId,
                    "operator_id" to request.operatorId,
                ),
            )
            .retrieve()
            .bodyToMono(JsonNode::class.java)
            .doOnError { error ->
                log.error(
                    "Operator debit api failed gameUserId={} userId={} operatorId={} txnId={} amount={} txnType={} gameId={} description={} reason={}",
                    request.gameUserId,
                    request.userId,
                    request.operatorId,
                    request.txnId,
                    request.amount,
                    request.txnType,
                    request.gameId,
                    request.description,
                    error.message ?: error.javaClass.simpleName,
                    error,
                )
            }
            .map { body ->
                if (!body.path("status").asBoolean(false)) {
                    throw DomainException(HttpStatus.BAD_GATEWAY, body.path("msg").asText("Operator debit failed."))
                }
                log.info(
                    "Operator debit api accepted gameUserId={} userId={} operatorId={} txnId={} amount={} description={} msg={}",
                    request.gameUserId,
                    request.userId,
                    request.operatorId,
                    request.txnId,
                    request.amount,
                    request.description,
                    body.path("msg").asText(""),
                )
                operatorGatewayLogStream.publish(
                    OperatorGatewayLogEvent(
                        id = "operator_debit_accepted:${request.txnId}",
                        eventType = "operator_debit_api_accepted",
                        action = "Operator debit API accepted",
                        gameUserId = request.gameUserId,
                        userId = request.userId,
                        operatorId = request.operatorId,
                        txnId = request.txnId,
                        amount = request.amount,
                        description = request.description,
                        target = operatorProperties.balancePath,
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
        if (message.txn_ref_id.startsWith("roomfee:")) {
            log.warn(
                "Blocked operator credit for legacy/unconfirmed debit reference gameUserId={} userId={} txnId={} txnRefId={} amount={}",
                message.gameUserId,
                message.user_id,
                message.txn_id,
                message.txn_ref_id,
                message.amount,
            )
            operatorGatewayLogStream.publish(
                OperatorGatewayLogEvent(
                    id = "operator_credit_blocked:${message.txn_id}",
                    eventType = "operator_credit_blocked_legacy_debit_ref",
                    action = "Operator credit blocked for legacy debit reference",
                    gameUserId = message.gameUserId,
                    userId = message.user_id,
                    operatorId = message.operatorId,
                    txnId = message.txn_id,
                    txnRefId = message.txn_ref_id,
                    amount = message.amount,
                    description = message.description,
                    target = "${operatorProperties.creditExchange}:${operatorProperties.creditRoutingKey}",
                    createdAt = Instant.now(),
                    ip = message.ip,
                    gameId = message.game_id,
                    exchange = operatorProperties.creditExchange,
                    routingKey = operatorProperties.creditRoutingKey,
                ),
            )
            return Mono.empty()
        }

        val payload = objectMapper.writeValueAsString(message)
        return Mono.fromRunnable<Void> {
            val amqpMessage = MessageBuilder.withBody(payload.toByteArray(Charsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding("UTF-8")
                .build()

            rabbitTemplate.convertAndSend(
                operatorProperties.creditExchange,
                operatorProperties.creditRoutingKey,
                amqpMessage,
            )
            operatorGatewayLogStream.publish(
                OperatorGatewayLogEvent(
                    id = "operator_credit:${message.txn_id}",
                    eventType = "operator_credit_enqueued",
                    action = "Operator credit enqueued",
                    gameUserId = message.gameUserId,
                    userId = message.user_id,
                    operatorId = message.operatorId,
                    txnId = message.txn_id,
                    txnRefId = message.txn_ref_id,
                    amount = message.amount,
                    description = message.description,
                    target = "${operatorProperties.creditExchange}:${operatorProperties.creditRoutingKey}",
                    createdAt = Instant.now(),
                    ip = message.ip,
                    gameId = message.game_id,
                    exchange = operatorProperties.creditExchange,
                    routingKey = operatorProperties.creditRoutingKey,
                ),
            )
            log.info(
                "Operator credit enqueued gameUserId={} userId={} operatorId={} txnId={} txnRefId={} amount={} gameId={} description={} exchange={} routingKey={}",
                message.gameUserId,
                message.user_id,
                message.operatorId,
                message.txn_id,
                message.txn_ref_id,
                message.amount,
                message.game_id,
                message.description,
                operatorProperties.creditExchange,
                operatorProperties.creditRoutingKey,
            )
        }.doOnError { error ->
            log.error(
                "Operator credit enqueue failed gameUserId={} userId={} operatorId={} txnId={} txnRefId={} amount={} gameId={} description={} reason={}",
                message.gameUserId,
                message.user_id,
                message.operatorId,
                message.txn_id,
                message.txn_ref_id,
                message.amount,
                message.game_id,
                message.description,
                error.message ?: error.javaClass.simpleName,
                error,
            )
        }
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
                action = "Existing operator debit reservation reused",
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

    private fun webClientFor(path: String): WebClient =
        if (path.trimStart().let { it.startsWith("/service/") || it.startsWith("/operator/service/") }) {
            serviceWebClient
        } else {
            webClient
        }

    private fun String.withoutDuplicateOperatorPrefix(): String =
        if (trimStart().startsWith("/operator/service/")) removePrefix("/operator") else this

    private fun String.removeTrailingPathSegment(segment: String): String {
        val uri = UriComponentsBuilder.fromUriString(this).build()
        val path = uri.path.orEmpty().trimEnd('/')
        if (!path.endsWith("/$segment")) {
            return this
        }

        val rootPath = path.removeSuffix("/$segment").ifBlank { null }
        val builder = UriComponentsBuilder.newInstance()
            .scheme(uri.scheme)
            .host(uri.host)

        if (uri.port >= 0) {
            builder.port(uri.port)
        }
        if (rootPath != null) {
            builder.path(rootPath)
        }

        return builder.build()
            .toUriString()
            .trimEnd('/')
    }

    private fun parseUserDetail(body: JsonNode): OperatorUserDetail {
        if (!body.path("status").asBoolean(false)) {
            throw DomainException(HttpStatus.BAD_GATEWAY, body.path("msg").asText("Operator user detail failed."))
        }

        val data = body.path("user")
            .takeUnless { it.isMissingNode || it.isNull }
            ?: body.path("data").takeUnless { it.isMissingNode || it.isNull }
            ?: body
        val userId = firstText(data, "user_id", "userId", "id")
        val displayName = firstText(data, "username", "display_name", "displayName", "name").ifBlank { userId }
        val currency = firstText(data, "currency").ifBlank { "INR" }
        val operatorId = firstText(data, "operator_id", "operatorId")
        val balance = firstDecimal(data, "balance", "available_balance", "availableBalance")

        if (userId.isBlank()) {
            throw DomainException(HttpStatus.BAD_GATEWAY, "Operator user detail response did not include user_id.")
        }
        if (operatorId.isBlank()) {
            throw DomainException(HttpStatus.BAD_GATEWAY, "Operator user detail response did not include operator_id.")
        }

        return OperatorUserDetail(
            userId = userId,
            displayName = displayName,
            balance = balance,
            currency = currency,
            operatorId = operatorId,
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

    private fun toGatewayError(error: WebClientResponseException): DomainException {
        val message = error.responseBodyAsString.takeIf { it.isNotBlank() }
            ?: "Operator gateway request failed with status ${error.statusCode.value()}."
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
