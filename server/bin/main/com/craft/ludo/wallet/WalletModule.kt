package com.craft.ludo.wallet

import com.craft.ludo.identity.SessionPrincipalResolver
import com.craft.ludo.identity.GuestSessionDocument
import com.craft.ludo.identity.GuestSessionRepository
import com.craft.ludo.operator.OperatorCreditQueueMessage
import com.craft.ludo.operator.OperatorDebitRequest
import com.craft.ludo.operator.OperatorGatewayClient
import com.craft.ludo.shared.api.DomainException
import com.craft.ludo.shared.config.AppProperties
import com.craft.ludo.shared.support.newId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.data.mongodb.repository.ReactiveMongoRepository
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant

enum class WalletTransactionType {
    GUEST_STARTING_BALANCE,
    ADMIN_CREDIT,
    ROOM_RESERVATION,
    ROOM_REFUND,
    MATCH_PAYOUT,
    HOUSE_RAKE,
}

@Document("wallet_accounts")
data class WalletAccountDocument(
    @Id
    val id: String = newId("wal"),
    val userId: String,
    val currency: String = "INR",
    val availableBalance: Long = 0,
    val reservedBalance: Long = 0,
    val updatedAt: Instant,
    @Version
    val version: Long? = null,
)

@Document("wallet_transactions")
data class WalletTransactionDocument(
    @Id
    val id: String = newId("txn"),
    val userId: String,
    val type: WalletTransactionType,
    val amount: Long,
    val currency: String,
    val referenceId: String,
    val description: String,
    val idempotencyKey: String?,
    val createdAt: Instant,
)

@Document("wallet_entries")
data class WalletEntryDocument(
    @Id
    val id: String = newId("entry"),
    val transactionId: String,
    val userId: String,
    val accountSide: String,
    val amount: Long,
    val createdAt: Instant,
)

@Document("idempotency_keys")
data class IdempotencyKeyDocument(
    @Id
    val id: String = newId("idem"),
    val scope: String,
    val key: String,
    val createdAt: Instant,
)

interface WalletAccountRepository : ReactiveMongoRepository<WalletAccountDocument, String> {
    fun findByUserId(userId: String): Mono<WalletAccountDocument>
}

interface WalletTransactionRepository : ReactiveMongoRepository<WalletTransactionDocument, String> {
    fun findByUserId(userId: String, sort: Sort): Flux<WalletTransactionDocument>
    fun existsByUserId(userId: String): Mono<Boolean>
    fun findFirstByUserIdAndTypeAndReferenceIdOrderByCreatedAtDesc(
        userId: String,
        type: WalletTransactionType,
        referenceId: String,
    ): Mono<WalletTransactionDocument>
}

interface WalletEntryRepository : ReactiveMongoRepository<WalletEntryDocument, String>

interface IdempotencyKeyRepository : ReactiveMongoRepository<IdempotencyKeyDocument, String> {
    fun existsByScopeAndKey(scope: String, key: String): Mono<Boolean>
}

data class WalletOverviewResponse(
    val userId: String,
    val currency: String,
    val availableBalance: Long,
    val reservedBalance: Long,
    val transactions: List<WalletTransactionDocument>,
)

data class AdminCreditRequest(
    val userId: String,
    val amount: Long,
    val reason: String,
    val idempotencyKey: String? = null,
)

data class WalletReservation(
    val userId: String,
    val transactionId: String,
    val amount: Long,
    val synthetic: Boolean = false,
    val externalDebitTransactionId: String? = null,
    val externalDebitConfirmed: Boolean = false,
    val operatorToken: String? = null,
    val operatorUserId: String? = null,
    val operatorId: String? = null,
    val ipAddress: String? = null,
    val gameId: Int? = null,
)

internal fun calculateWinnerLedgerPayoutAmount(
    winnerUserId: String,
    paidReservations: List<WalletReservation>,
    payoutRakeBasisPoints: Int,
): Long {
    val potAmount = paidReservations.sumOf { it.amount }
    val winnerPayoutAmount = potAmount - calculateRakeAmount(potAmount, payoutRakeBasisPoints)
    val winnerReservation = paidReservations.lastOrNull { it.userId == winnerUserId }
    val winnerUsesOperatorWallet = winnerReservation?.operatorUserId != null ||
        winnerReservation?.operatorToken != null ||
        winnerReservation?.operatorId != null
    val winnerHasConfirmedExternalDebit = winnerReservation?.externalDebitTransactionId != null &&
        winnerReservation.externalDebitConfirmed

    return if (winnerUsesOperatorWallet && !winnerHasConfirmedExternalDebit) {
        0L
    } else if (winnerHasConfirmedExternalDebit) {
        winnerPayoutAmount
    } else {
        winnerPayoutAmount
    }
}

internal fun calculateRakeAmount(
    potAmount: Long,
    payoutRakeBasisPoints: Int,
): Long {
    if (payoutRakeBasisPoints == 0) return 0

    return (potAmount * payoutRakeBasisPoints) / 10_000
}

@org.springframework.stereotype.Service
class WalletService(
    private val walletAccountRepository: WalletAccountRepository,
    private val walletTransactionRepository: WalletTransactionRepository,
    private val walletEntryRepository: WalletEntryRepository,
    private val idempotencyKeyRepository: IdempotencyKeyRepository,
    private val guestSessionRepository: GuestSessionRepository,
    private val operatorGatewayClient: OperatorGatewayClient,
    private val mongoTemplate: ReactiveMongoTemplate,
    private val transactionalOperator: TransactionalOperator,
    private val clock: Clock,
    appProperties: AppProperties,
) {
    private val log = LoggerFactory.getLogger(WalletService::class.java)
    private val walletCurrency = appProperties.wallet.currency.trim().uppercase()
    private val guestStartingBalance = appProperties.wallet.guestStartingBalance
    private val payoutRakeBasisPoints = appProperties.wallet.payoutRakeBasisPoints
    private val houseUserId = appProperties.wallet.houseUserId.trim()

    init {
        require(walletCurrency.isNotBlank()) { "app.wallet.currency must not be blank." }
        require(guestStartingBalance >= 0) { "app.wallet.guest-starting-balance must not be negative." }
        require(payoutRakeBasisPoints in 0..10_000) {
            "app.wallet.payout-rake-basis-points must be between 0 and 10000."
        }
        require(houseUserId.isNotBlank()) { "app.wallet.house-user-id must not be blank." }
    }

    fun initializeGuestWallet(userId: String): Mono<WalletAccountDocument> {
        val now = Instant.now(clock)
        return walletAccountRepository.findByUserId(userId)
            .switchIfEmpty(
                Mono.defer {
                    val newAccount = WalletAccountDocument(
                        userId = userId,
                        currency = walletCurrency,
                        availableBalance = guestStartingBalance,
                        reservedBalance = 0L,
                        updatedAt = now,
                    )

                    walletAccountRepository.save(newAccount)
                        .flatMap { createdAccount ->
                            if (guestStartingBalance <= 0) {
                                Mono.just(createdAccount)
                            } else {
                                persistLedger(
                                    userId = userId,
                                    amount = guestStartingBalance,
                                    type = WalletTransactionType.GUEST_STARTING_BALANCE,
                                    referenceId = userId,
                                    description = "Guest starting balance",
                                    idempotencyKey = "seed:$userId",
                                ).thenReturn(createdAccount)
                            }
                        }
                },
            )
    }

    fun ensureAccount(userId: String): Mono<WalletAccountDocument> {
        val now = Instant.now(clock)
        val query = Query.query(Criteria.where("userId").`is`(userId))
        val update = Update()
            .setOnInsert("id", newId("wal"))
            .setOnInsert("userId", userId)
            .setOnInsert("currency", walletCurrency)
            .setOnInsert("availableBalance", 0L)
            .setOnInsert("reservedBalance", 0L)
            .setOnInsert("updatedAt", now)

        return mongoTemplate.findAndModify(
            query,
            update,
            FindAndModifyOptions.options().upsert(true).returnNew(true),
            WalletAccountDocument::class.java,
        )
    }

    fun initializeOperatorWallet(
        userId: String,
        balance: BigDecimal,
        currency: String,
    ): Mono<WalletAccountDocument> {
        val now = Instant.now(clock)
        val query = Query.query(Criteria.where("userId").`is`(userId))
        val update = Update()
            .setOnInsert("id", newId("wal"))
            .setOnInsert("userId", userId)
            .set("currency", currency.trim().uppercase().ifBlank { walletCurrency })
            .set("availableBalance", balance.toWalletAmount())
            .set("reservedBalance", 0L)
            .set("updatedAt", now)

        return mongoTemplate.findAndModify(
            query,
            update,
            FindAndModifyOptions.options().upsert(true).returnNew(true),
            WalletAccountDocument::class.java,
        )
    }

    fun getOverview(userId: String): Mono<WalletOverviewResponse> {
        return refreshOperatorWalletBalance(userId)
            .switchIfEmpty(ensureGuestWalletBalance(userId))
            .flatMap { account ->
            walletTransactionRepository.findByUserId(
                userId,
                Sort.by(Sort.Direction.DESC, "createdAt"),
            )
                .take(8)
                .collectList()
                .map { transactions ->
                    WalletOverviewResponse(
                        userId = userId,
                        currency = account.currency,
                        availableBalance = account.availableBalance,
                        reservedBalance = account.reservedBalance,
                        transactions = transactions,
                    )
                }
        }
    }

    private fun ensureGuestWalletBalance(userId: String): Mono<WalletAccountDocument> {
        return initializeGuestWallet(userId)
            .flatMap { account ->
                if (
                    userId == houseUserId ||
                    guestStartingBalance <= 0 ||
                    account.availableBalance > 0 ||
                    account.reservedBalance > 0
                ) {
                    Mono.just(account)
                } else {
                    walletTransactionRepository.existsByUserId(userId)
                        .flatMap { hasTransactions ->
                            if (hasTransactions) {
                                Mono.just(account)
                            } else {
                                val scope = "wallet:guest-reseed:$userId"
                                claimIdempotency(scope, "reseed")
                                    .then(adjustBalances(userId, guestStartingBalance, 0))
                                    .flatMap { updatedAccount ->
                                        persistLedger(
                                            userId = userId,
                                            amount = guestStartingBalance,
                                            type = WalletTransactionType.GUEST_STARTING_BALANCE,
                                            referenceId = userId,
                                            description = "Guest starting balance",
                                            idempotencyKey = "reseed:$userId",
                                        ).thenReturn(updatedAccount)
                                    }
                                    .onErrorResume(DomainException::class.java) { error ->
                                        if (error.status == HttpStatus.CONFLICT) {
                                            ensureAccount(userId)
                                        } else {
                                            Mono.error(error)
                                        }
                                    }
                            }
                        }
                }
            }
    }

    fun adminCredit(request: AdminCreditRequest): Mono<WalletOverviewResponse> {
        require(request.amount > 0) { "Admin credit amount must be positive." }

        val scope = "wallet:admin-credit:${request.userId}"
        val workflow = claimIdempotency(scope, request.idempotencyKey)
            .then(adjustBalances(request.userId, request.amount, 0))
            .flatMap { account ->
                persistLedger(
                    userId = request.userId,
                    amount = request.amount,
                    type = WalletTransactionType.ADMIN_CREDIT,
                    referenceId = newId("admin_credit"),
                    description = request.reason,
                    idempotencyKey = request.idempotencyKey,
                ).thenReturn(account)
            }
            .then(getOverview(request.userId))

        return transactionalOperator.transactional(workflow)
    }

    fun reserveEntryFee(
        userId: String,
        roomId: String,
        amount: Long,
        ipAddress: String?,
    ): Mono<WalletReservation> {
        require(amount > 0) { "Entry fee amount must be positive." }

        val transactionId = newId("roomfee")
        log.info(
            "Ludo entry fee reservation requested userId={} roomId={} amount={} transactionId={}",
            userId,
            roomId,
            amount,
            transactionId,
        )
        return createEntryFeeReservation(userId, roomId, amount, ipAddress, transactionId)
    }

    private fun createEntryFeeReservation(
        userId: String,
        roomId: String,
        amount: Long,
        ipAddress: String?,
        transactionId: String,
    ): Mono<WalletReservation> {
        val idempotencyScope = "wallet:room-reservation:$roomId:$userId"
        val reservationKey = "amount:$amount"

        return claimIdempotency(idempotencyScope, reservationKey)
            .then(
                operatorSessionForUser(userId)
            .flatMap { operatorSession ->
                val debitTransactionId = transactionId
                val debitDescription = ludoDebitStatement(amount, debitTransactionId, roomId)
                log.info(
                    "Ludo operator entry fee debit requested userId={} operatorUserId={} operatorId={} roomId={} amount={} transactionId={} description={}",
                    userId,
                    operatorSession.operatorUserId,
                    operatorSession.operatorId,
                    roomId,
                    amount,
                    debitTransactionId,
                    debitDescription,
                )
                operatorGatewayClient.debit(
                    OperatorDebitRequest(
                        gameUserId = userId,
                        txnId = debitTransactionId,
                        amount = amount.toExternalAmount(),
                        description = debitDescription,
                        ip = ipAddress ?: "0.0.0.0",
                        gameId = operatorSession.operatorGameId ?: operatorGatewayClient.gameId(),
                        userId = operatorSession.operatorUserId!!,
                        operatorId = operatorSession.operatorId!!,
                        token = operatorSession.operatorToken!!,
                    ),
                )
                    .doOnError { error ->
                        log.error(
                            "Operator entry fee debit failed userId={} operatorUserId={} operatorId={} roomId={} amount={} gameId={} transactionId={} reason={}",
                            userId,
                            operatorSession.operatorUserId,
                            operatorSession.operatorId,
                            roomId,
                            amount,
                            operatorSession.operatorGameId ?: operatorGatewayClient.gameId(),
                            debitTransactionId,
                            error.message ?: error.javaClass.simpleName,
                            error,
                        )
                    }
                    .onErrorResume { error ->
                        releaseIdempotency(idempotencyScope, reservationKey)
                            .then(Mono.error(error))
                    }
                    .then(
                        persistLedger(
                            userId = userId,
                            amount = amount,
                            type = WalletTransactionType.ROOM_RESERVATION,
                            referenceId = roomId,
                            description = debitDescription,
                            idempotencyKey = reservationKey,
                            transactionIdOverride = transactionId,
                        ).thenReturn(
                            WalletReservation(
                                userId = userId,
                                transactionId = transactionId,
                                amount = amount,
                                externalDebitTransactionId = debitTransactionId,
                                externalDebitConfirmed = true,
                                operatorToken = operatorSession.operatorToken,
                                operatorUserId = operatorSession.operatorUserId,
                                operatorId = operatorSession.operatorId,
                                ipAddress = ipAddress ?: "0.0.0.0",
                                gameId = operatorSession.operatorGameId ?: operatorGatewayClient.gameId(),
                            ),
                        )
                    )
            }
            .switchIfEmpty(
                Mono.defer {
                    val workflow = adjustBalances(userId, -amount, amount)
                        .switchIfEmpty(Mono.error(DomainException(HttpStatus.CONFLICT, "Insufficient wallet balance.")))
                        .flatMap {
                            persistLedger(
                                userId = userId,
                                amount = amount,
                                type = WalletTransactionType.ROOM_RESERVATION,
                                referenceId = roomId,
                                description = ludoDebitStatement(amount, transactionId, roomId),
                                idempotencyKey = reservationKey,
                                transactionIdOverride = transactionId,
                            ).thenReturn(WalletReservation(userId, transactionId, amount))
                        }

                    transactionalOperator.transactional(workflow)
                        .onErrorResume { error ->
                            releaseIdempotency(idempotencyScope, reservationKey)
                                .then(Mono.error(error))
                        }
                },
            )
            )
            .onErrorResume(DomainException::class.java) { error ->
                if (error.status != HttpStatus.CONFLICT || !error.message.orEmpty().contains("Duplicate idempotency key")) {
                    return@onErrorResume Mono.error(error)
                }

                awaitExistingRoomReservation(userId, roomId, amount, attemptsRemaining = 10)
            }
    }

    private fun awaitExistingRoomReservation(
        userId: String,
        roomId: String,
        amount: Long,
        attemptsRemaining: Int,
    ): Mono<WalletReservation> {
        return findExistingRoomReservation(userId, roomId, amount)
            .switchIfEmpty(
                if (attemptsRemaining <= 0) {
                    Mono.error(
                        DomainException(
                            HttpStatus.CONFLICT,
                            "Entry fee reservation is already in progress for room $roomId.",
                        ),
                    )
                } else {
                    Mono.delay(Duration.ofMillis(100))
                        .then(awaitExistingRoomReservation(userId, roomId, amount, attemptsRemaining - 1))
                },
            )
    }

    private fun findExistingRoomReservation(
        userId: String,
        roomId: String,
        amount: Long,
    ): Mono<WalletReservation> {
        return walletTransactionRepository
            .findFirstByUserIdAndTypeAndReferenceIdOrderByCreatedAtDesc(
                userId,
                WalletTransactionType.ROOM_RESERVATION,
                roomId,
            )
            .filter { transaction -> transaction.amount == amount }
            .flatMap { transaction ->
                operatorSessionForUser(userId)
                    .map { operatorSession ->
                        operatorGatewayClient.publishExistingDebitReservation(
                            gameUserId = userId,
                            operatorUserId = operatorSession.operatorUserId!!,
                            operatorId = operatorSession.operatorId!!,
                            txnId = transaction.id,
                            amount = amount.toExternalAmount(),
                            description = ludoDebitStatement(amount, transaction.id, roomId),
                            ip = "0.0.0.0",
                            gameId = operatorSession.operatorGameId ?: operatorGatewayClient.gameId(),
                        )
                        WalletReservation(
                            userId = userId,
                            transactionId = transaction.id,
                            amount = amount,
                            externalDebitTransactionId = transaction.id,
                            externalDebitConfirmed = true,
                            operatorToken = operatorSession.operatorToken,
                            operatorUserId = operatorSession.operatorUserId,
                            operatorId = operatorSession.operatorId,
                            ipAddress = "0.0.0.0",
                            gameId = operatorSession.operatorGameId ?: operatorGatewayClient.gameId(),
                        )
                    }
                    .defaultIfEmpty(WalletReservation(userId, transaction.id, amount))
            }
    }

    fun refundReservation(reservation: WalletReservation, roomId: String): Mono<Void> {
        val hasConfirmedExternalDebit = reservation.externalDebitTransactionId != null &&
            reservation.externalDebitConfirmed
        val releaseReservedBalance = if (!hasConfirmedExternalDebit) {
            adjustBalances(reservation.userId, reservation.amount, -reservation.amount)
                .switchIfEmpty(
                    Mono.error(DomainException(HttpStatus.CONFLICT, "Reserved balance not available for refund.")),
                )
                .then()
        } else {
            Mono.empty()
        }

        val workflow = enqueueExternalCreditIfNeeded(
            reservation = reservation,
            amount = reservation.amount,
            txnId = newId("txn"),
            description = ludoCreditStatement(reservation.amount, roomId, "refund", roomId),
        )
            .then(releaseReservedBalance)
            .then(
                persistLedger(
                    userId = reservation.userId,
                    amount = reservation.amount,
                    type = WalletTransactionType.ROOM_REFUND,
                    referenceId = roomId,
                    description = ludoCreditStatement(reservation.amount, roomId, "refund", roomId),
                    idempotencyKey = reservation.transactionId,
                ),
            )

        return transactionalOperator.transactional(workflow.then())
    }

    fun payoutWinner(
        matchId: String,
        winnerUserId: String,
        reservations: List<WalletReservation>,
    ): Mono<Void> {
        val paidReservations = reservations.filter { it.amount > 0 }
        val realPaidReservations = paidReservations.filterNot { it.synthetic }
        val externallyDebitedReservations = realPaidReservations.filter { reservation ->
            reservation.externalDebitTransactionId != null && reservation.externalDebitConfirmed
        }
        val localPaidReservations = realPaidReservations - externallyDebitedReservations.toSet()
        val potAmount = paidReservations.sumOf { it.amount }
        val confirmedExternalPotAmount = externallyDebitedReservations.sumOf { it.amount }
        if (potAmount <= 0) return Mono.empty()
        val rakeAmount = calculateRakeAmount(potAmount)
        val winnerPayoutAmount = potAmount - rakeAmount
        val winnerReservation = paidReservations.lastOrNull { it.userId == winnerUserId }
        val winnerIsSynthetic = winnerReservation?.synthetic == true
        val winnerUsesOperatorWallet = winnerReservation?.operatorUserId != null ||
            winnerReservation?.operatorToken != null ||
            winnerReservation?.operatorId != null
        val winnerHasConfirmedExternalDebit = winnerReservation?.externalDebitTransactionId != null &&
            winnerReservation.externalDebitConfirmed
        val winnerLedgerPayoutAmount = calculateWinnerLedgerPayoutAmount(
            winnerUserId = winnerUserId,
            paidReservations = paidReservations,
            payoutRakeBasisPoints = payoutRakeBasisPoints,
        )
        log.info(
            "Ludo wallet settlement requested matchId={} winnerUserId={} reservations={} realReservations={} confirmedExternalReservations={} potAmount={} confirmedExternalPotAmount={} winnerUsesOperatorWallet={} winnerHasConfirmedExternalDebit={} payoutAmount={}",
            matchId,
            winnerUserId,
            paidReservations.size,
            realPaidReservations.size,
            externallyDebitedReservations.size,
            potAmount,
            confirmedExternalPotAmount,
            winnerUsesOperatorWallet,
            winnerHasConfirmedExternalDebit,
            winnerLedgerPayoutAmount,
        )

        val workflow = claimIdempotency("wallet:settlement:$matchId", matchId)
            .thenMany(
                Flux.fromIterable(localPaidReservations).concatMap { reservation ->
                    adjustBalances(reservation.userId, 0, -reservation.amount)
                        .switchIfEmpty(
                            Mono.error(
                                DomainException(
                                    HttpStatus.CONFLICT,
                                    "Reserved balance release failed for user ${reservation.userId}.",
                                ),
                            ),
                    )
                },
            )
            .then(
                if (winnerIsSynthetic) {
                    Mono.empty()
                } else if (winnerLedgerPayoutAmount <= 0) {
                    Mono.empty()
                } else {
                    enqueueWinnerExternalCreditIfNeeded(
                        matchId,
                        winnerUserId,
                        winnerLedgerPayoutAmount,
                        externallyDebitedReservations,
                    )
                        .then(persistWinnerPayout(matchId, winnerUserId, winnerLedgerPayoutAmount))
                },
            )
            .then(persistHouseRake(matchId, rakeAmount))

        return transactionalOperator.transactional(workflow.then())
    }

    private fun adjustBalances(
        userId: String,
        availableDelta: Long,
        reservedDelta: Long,
    ): Mono<WalletAccountDocument> {
        val criteria = Criteria.where("userId").`is`(userId)
        if (availableDelta < 0) {
            criteria.and("availableBalance").gte(-availableDelta)
        }
        if (reservedDelta < 0) {
            criteria.and("reservedBalance").gte(-reservedDelta)
        }

        val update = Update()
            .inc("availableBalance", availableDelta)
            .inc("reservedBalance", reservedDelta)
            .set("updatedAt", Instant.now(clock))
            .setOnInsert("id", newId("wal"))
            .setOnInsert("userId", userId)
            .setOnInsert("currency", walletCurrency)

        return mongoTemplate.findAndModify(
            Query.query(criteria),
            update,
            FindAndModifyOptions.options().upsert(true).returnNew(true),
            WalletAccountDocument::class.java,
        )
    }

    private fun persistLedger(
        userId: String,
        amount: Long,
        type: WalletTransactionType,
        referenceId: String,
        description: String,
        idempotencyKey: String?,
        transactionIdOverride: String? = null,
    ): Mono<Void> {
        val now = Instant.now(clock)
        val transactionId = if (
            type == WalletTransactionType.ROOM_RESERVATION &&
            transactionIdOverride?.startsWith("roomfee:") == true
        ) {
            val correctedId = newId("roomfee")
            log.error(
                "Corrected legacy room reservation transaction id userId={} referenceId={} legacyTransactionId={} correctedTransactionId={}",
                userId,
                referenceId,
                transactionIdOverride,
                correctedId,
            )
            correctedId
        } else {
            transactionIdOverride ?: newId("txn")
        }
        val transaction = WalletTransactionDocument(
            id = transactionId,
            userId = userId,
            type = type,
            amount = amount,
            currency = walletCurrency,
            referenceId = referenceId,
            description = description,
            idempotencyKey = idempotencyKey,
            createdAt = now,
        )

        val entries = listOf(
            WalletEntryDocument(
                transactionId = transaction.id,
                userId = userId,
                accountSide = if (
                    type == WalletTransactionType.MATCH_PAYOUT ||
                    type == WalletTransactionType.ADMIN_CREDIT ||
                    type == WalletTransactionType.HOUSE_RAKE
                ) {
                    "AVAILABLE_CREDIT"
                } else {
                    "ENTRY"
                },
                amount = amount,
                createdAt = now,
            ),
        )

        return walletTransactionRepository.save(transaction)
            .thenMany(walletEntryRepository.saveAll(entries))
            .then()
    }

    private fun claimIdempotency(scope: String, idempotencyKey: String?): Mono<Void> {
        if (idempotencyKey.isNullOrBlank()) return Mono.empty()

        return mongoTemplate.insert(
            IdempotencyKeyDocument(
                id = "$scope:$idempotencyKey",
                scope = scope,
                key = idempotencyKey,
                createdAt = Instant.now(clock),
            ),
        )
            .then()
            .onErrorMap(DuplicateKeyException::class.java) {
                DomainException(HttpStatus.CONFLICT, "Duplicate idempotency key for scope $scope")
            }
    }

    private fun releaseIdempotency(scope: String, idempotencyKey: String?): Mono<Void> {
        if (idempotencyKey.isNullOrBlank()) return Mono.empty()

        return mongoTemplate.remove(
            Query.query(
                Criteria.where("id").`is`("$scope:$idempotencyKey")
                    .and("scope").`is`(scope)
                    .and("key").`is`(idempotencyKey),
            ),
            IdempotencyKeyDocument::class.java,
        ).then()
    }

    private fun calculateRakeAmount(potAmount: Long): Long {
        return calculateRakeAmount(potAmount, payoutRakeBasisPoints)
    }

    private fun persistWinnerPayout(
        matchId: String,
        winnerUserId: String,
        winnerPayoutAmount: Long,
    ): Mono<Void> {
        if (winnerPayoutAmount <= 0) return Mono.empty()

        return adjustBalances(winnerUserId, winnerPayoutAmount, 0)
            .flatMap {
                persistLedger(
                    userId = winnerUserId,
                    amount = winnerPayoutAmount,
                    type = WalletTransactionType.MATCH_PAYOUT,
                    referenceId = matchId,
                    description = ludoCreditStatement(winnerPayoutAmount, matchId, "winner payout"),
                    idempotencyKey = matchId,
                )
            }
    }

    private fun persistHouseRake(
        matchId: String,
        rakeAmount: Long,
    ): Mono<Void> {
        if (rakeAmount <= 0) return Mono.empty()

        return adjustBalances(houseUserId, rakeAmount, 0)
            .flatMap {
                persistLedger(
                    userId = houseUserId,
                    amount = rakeAmount,
                    type = WalletTransactionType.HOUSE_RAKE,
                    referenceId = matchId,
                    description = "House rake for match $matchId",
                    idempotencyKey = "rake:$matchId",
                )
            }
    }

    private fun refreshOperatorWalletBalance(userId: String): Mono<WalletAccountDocument> {
        return operatorSessionForUser(userId)
            .flatMap { session ->
                operatorGatewayClient.fetchUserDetail(session.operatorToken!!)
                    .flatMap { detail ->
                        val query = Query.query(Criteria.where("userId").`is`(userId))
                        val update = Update()
                            .setOnInsert("id", newId("wal"))
                            .setOnInsert("userId", userId)
                            .set("currency", detail.currency.trim().uppercase().ifBlank { walletCurrency })
                            .set("availableBalance", detail.balance.toWalletAmount())
                            .set("updatedAt", Instant.now(clock))

                        mongoTemplate.findAndModify(
                            query,
                            update,
                            FindAndModifyOptions.options().upsert(true).returnNew(true),
                            WalletAccountDocument::class.java,
                        )
                    }
            }
    }

    private fun operatorSessionForUser(userId: String): Mono<GuestSessionDocument> {
        return guestSessionRepository.findFirstByUserIdOrderByUpdatedAtDesc(userId)
            .filter { session ->
                session.expiresAt.isAfter(Instant.now(clock)) &&
                    !session.operatorToken.isNullOrBlank() &&
                    !session.operatorUserId.isNullOrBlank() &&
                    !session.operatorId.isNullOrBlank()
            }
    }

    private fun enqueueWinnerExternalCreditIfNeeded(
        matchId: String,
        winnerUserId: String,
        winnerPayoutAmount: Long,
        reservations: List<WalletReservation>,
    ): Mono<Void> {
        if (winnerPayoutAmount <= 0) return Mono.empty()

        val winnerReservation = reservations
            .filter { reservation ->
                reservation.userId == winnerUserId &&
                    reservation.externalDebitTransactionId != null &&
                    reservation.externalDebitConfirmed
            }
            .lastOrNull()
            ?: return Mono.empty()

        return enqueueExternalCreditIfNeeded(
            reservation = winnerReservation,
            amount = winnerPayoutAmount,
            txnId = newId("txn"),
            description = ludoCreditStatement(winnerPayoutAmount, matchId, "winner payout"),
        )
    }

    private fun enqueueExternalCreditIfNeeded(
        reservation: WalletReservation,
        amount: Long,
        txnId: String,
        description: String,
    ): Mono<Void> {
        val debitTransactionId = reservation.externalDebitTransactionId ?: return Mono.empty()
        if (!reservation.externalDebitConfirmed) return Mono.empty()
        val operatorToken = reservation.operatorToken ?: return Mono.empty()
        val operatorUserId = reservation.operatorUserId ?: return Mono.empty()
        val operatorId = reservation.operatorId ?: return Mono.empty()

        return operatorGatewayClient.enqueueCredit(
            OperatorCreditQueueMessage(
                gameUserId = reservation.userId,
                amount = amount.toExternalAmount(),
                txn_id = txnId,
                txn_ref_id = debitTransactionId,
                ip = reservation.ipAddress ?: "0.0.0.0",
                game_id = reservation.gameId ?: operatorGatewayClient.gameId(),
                user_id = operatorUserId,
                operatorId = operatorId,
                token = operatorToken,
                description = description,
            ),
        )
    }

    private fun BigDecimal.toWalletAmount(): Long = setScale(0, java.math.RoundingMode.DOWN).toLong()

    private fun Long.toExternalAmount(): BigDecimal = BigDecimal.valueOf(this).setScale(2)

    private fun ludoDebitStatement(amount: Long, roundId: String, lobbyId: String): String =
        "Amount ${amount.toExternalAmount()} debited for Ludo game round $roundId lobby $lobbyId"

    private fun ludoCreditStatement(amount: Long, roundId: String, reason: String, lobbyId: String? = null): String {
        val lobbySuffix = lobbyId?.let { " lobby $it" }.orEmpty()
        return "Amount ${amount.toExternalAmount()} credited for Ludo game round $roundId$lobbySuffix $reason"
    }
}

@RestController
@RequestMapping("/api/v1/wallet", produces = [MediaType.APPLICATION_JSON_VALUE])
class WalletController(
    private val sessionPrincipalResolver: SessionPrincipalResolver,
    private val walletService: WalletService,
) {
    @GetMapping
    fun getWallet(
        @RequestHeader("X-Session-Token") sessionToken: String,
    ): Mono<WalletOverviewResponse> {
        return sessionPrincipalResolver.requireUser(sessionToken)
            .flatMap { walletService.getOverview(it.id) }
    }
}

@RestController
@RequestMapping("/api/v1/admin", produces = [MediaType.APPLICATION_JSON_VALUE])
class AdminWalletController(
    private val walletService: WalletService,
) {
    @PostMapping("/credit")
    fun creditWallet(
        @RequestBody request: AdminCreditRequest,
    ): Mono<WalletOverviewResponse> = walletService.adminCredit(request)
}
