package com.craft.ludo.identity

import com.craft.ludo.shared.api.DomainException
import com.craft.ludo.operator.OperatorGatewayClient
import com.craft.ludo.session.SessionBindingService
import com.craft.ludo.shared.config.AppProperties
import com.craft.ludo.shared.support.newId
import com.craft.ludo.wallet.WalletService
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.repository.ReactiveMongoRepository
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

@Document("guest_sessions")
data class GuestSessionDocument(
    @Id
    val id: String = newId("sess"),
    val sessionToken: String = newId("token"),
    val userId: String = newId("guest"),
    val displayName: String,
    val operatorToken: String? = null,
    val operatorUserId: String? = null,
    val operatorId: String? = null,
    val operatorCurrency: String? = null,
    val operatorGameId: Int? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val expiresAt: Instant,
)

interface GuestSessionRepository : ReactiveMongoRepository<GuestSessionDocument, String> {
    fun findBySessionToken(sessionToken: String): Mono<GuestSessionDocument>
    fun findFirstByUserIdOrderByUpdatedAtDesc(userId: String): Mono<GuestSessionDocument>
}

data class SessionPrincipal(
    val id: String,
    val sessionToken: String,
    val displayName: String,
    val ipAddress: String? = null,
    val operatorUserId: String? = null,
    val operatorId: String? = null,
)

data class CreateGuestSessionRequest(
    val displayName: String? = null,
)

data class UpdateGuestProfileRequest(
    val displayName: String,
)

data class GuestSessionResponse(
    val userId: String,
    val sessionToken: String,
    val displayName: String,
    val expiresAt: Instant,
    val isOperatorSession: Boolean = false,
)

data class ValidateSessionRequest(
    val sessionToken: String,
    val gameId: Int? = null,
)

@Service
class IdentityService(
    private val guestSessionRepository: GuestSessionRepository,
    private val walletService: WalletService,
    private val operatorGatewayClient: OperatorGatewayClient,
    private val sessionBindingService: SessionBindingService,
    private val clock: Clock,
    appProperties: AppProperties,
) {
    private val sessionTtlDays = appProperties.session.ttlDays
    private val externalSessionServiceEnabled = appProperties.sessionService.enabled

    init {
        require(sessionTtlDays > 0) { "app.session.ttl-days must be positive." }
    }

    fun createGuestSession(request: CreateGuestSessionRequest): Mono<GuestSessionResponse> {
        val now = Instant.now(clock)
        val session = GuestSessionDocument(
            displayName = resolveDisplayName(request.displayName, newId("guest")),
            createdAt = now,
            updatedAt = now,
            expiresAt = now.plus(sessionTtlDays, ChronoUnit.DAYS),
        )

        val persistedSession = session.copy(
            displayName = resolveDisplayName(request.displayName, session.userId),
        )

        return guestSessionRepository.save(persistedSession)
            .flatMap { savedSession ->
                walletService.initializeGuestWallet(savedSession.userId).thenReturn(savedSession)
            }
            .map(::toResponse)
    }

    fun validateExternalSession(sessionToken: String, gameId: Int? = null): Mono<GuestSessionResponse> {
        if (!externalSessionServiceEnabled) {
            return Mono.error(
                DomainException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "External session service is disabled. Enable APP_SESSION_SERVICE_ENABLED or use guest mode.",
                ),
            )
        }
        val normalizedToken = sessionToken.trim()
        if (normalizedToken.isBlank()) {
            return Mono.error(DomainException(HttpStatus.BAD_REQUEST, "sessionToken is required."))
        }

        val resolvedGameId = gameId ?: operatorGatewayClient.gameId()
        return sessionBindingService.validateAndBind(normalizedToken, resolvedGameId)
            .flatMap { validated ->
                walletService.initializeOperatorWallet(
                    userId = validated.userId,
                    balance = validated.balance,
                    currency = validated.currency,
                ).thenReturn(validated)
            }
            .map { validated ->
                GuestSessionResponse(
                    userId = validated.userId,
                    sessionToken = validated.sessionToken,
                    displayName = validated.displayName,
                    expiresAt = Instant.now(clock).plus(16, ChronoUnit.HOURS),
                    isOperatorSession = true,
                )
            }
    }

    fun getCurrentSession(sessionToken: String): Mono<GuestSessionResponse> {
        return findActiveSession(sessionToken)
            .map(::toResponse)
    }

    fun updateProfile(
        sessionToken: String,
        request: UpdateGuestProfileRequest,
    ): Mono<GuestSessionResponse> {
        val normalizedDisplayName = normalizeDisplayName(request.displayName)

        return findActiveSession(sessionToken)
            .flatMap { session ->
                guestSessionRepository.save(
                    session.copy(
                        displayName = normalizedDisplayName,
                        updatedAt = Instant.now(clock),
                    ),
                )
            }
            .map(::toResponse)
    }

    fun findActiveSession(sessionToken: String): Mono<GuestSessionDocument> {
        val trimmedToken = sessionToken.trim()
        if (trimmedToken.isEmpty()) {
            return Mono.error(DomainException(HttpStatus.UNAUTHORIZED, "Missing session token."))
        }

        if (externalSessionServiceEnabled) {
            return sessionBindingService.validateAndBind(trimmedToken)
                .map { validated ->
                    GuestSessionDocument(
                        userId = validated.userId,
                        sessionToken = validated.sessionToken,
                        displayName = validated.displayName,
                        operatorToken = validated.sessionToken,
                        operatorUserId = validated.operatorUserId ?: validated.userId,
                        operatorId = validated.operatorId,
                        operatorCurrency = validated.currency,
                        operatorGameId = validated.gameId,
                        createdAt = Instant.now(clock),
                        updatedAt = Instant.now(clock),
                        expiresAt = Instant.now(clock).plus(16, ChronoUnit.HOURS),
                    )
                }
        }

        return guestSessionRepository.findBySessionToken(trimmedToken)
            .switchIfEmpty(Mono.error(DomainException(HttpStatus.UNAUTHORIZED, "Session not found.")))
            .flatMap { session ->
                if (session.expiresAt.isAfter(Instant.now(clock))) {
                    Mono.just(session)
                } else {
                    Mono.error(DomainException(HttpStatus.UNAUTHORIZED, "Session expired."))
                }
            }
    }

    private fun toResponse(session: GuestSessionDocument): GuestSessionResponse {
        return GuestSessionResponse(
            userId = session.userId,
            sessionToken = session.sessionToken,
            displayName = session.displayName,
            expiresAt = session.expiresAt,
            isOperatorSession = !session.operatorToken.isNullOrBlank(),
        )
    }

    private fun resolveDisplayName(rawDisplayName: String?, userId: String): String {
        val trimmed = rawDisplayName?.trim().orEmpty()
        if (trimmed.isNotEmpty()) {
            return normalizeDisplayName(trimmed)
        }

        return "Guest ${userId.takeLast(4).uppercase()}"
    }

    private fun normalizeDisplayName(rawDisplayName: String): String {
        val normalized = rawDisplayName.trim().replace(Regex("\\s+"), " ")
        require(normalized.length in 3..24) {
            "Display name must be between 3 and 24 characters."
        }
        return normalized
    }

}

@Component
class SessionPrincipalResolver(
    private val identityService: IdentityService,
) {
    fun requireUser(sessionToken: String): Mono<SessionPrincipal> {
        return identityService.findActiveSession(sessionToken)
            .map { session ->
                SessionPrincipal(
                    id = session.userId,
                    sessionToken = session.sessionToken,
                    displayName = session.displayName,
                    operatorUserId = session.operatorUserId,
                    operatorId = session.operatorId,
                )
            }
    }
}

@RestController
@RequestMapping("/api/v1/identity", produces = [MediaType.APPLICATION_JSON_VALUE])
class IdentityController(
    private val identityService: IdentityService,
) {
    @PostMapping("/guest")
    fun createGuestSession(
        @RequestBody(required = false) request: CreateGuestSessionRequest?,
    ): Mono<GuestSessionResponse> {
        return identityService.createGuestSession(request ?: CreateGuestSessionRequest())
    }

    @PostMapping("/session/validate")
    fun validateSession(
        @RequestBody request: ValidateSessionRequest,
    ): Mono<GuestSessionResponse> {
        return identityService.validateExternalSession(request.sessionToken, request.gameId)
    }

    @GetMapping("/me")
    fun getCurrentSession(
        @RequestHeader("X-Session-Token") sessionToken: String,
    ): Mono<GuestSessionResponse> {
        return identityService.getCurrentSession(sessionToken)
    }

    @PatchMapping("/profile")
    fun updateProfile(
        @RequestHeader("X-Session-Token") sessionToken: String,
        @RequestBody request: UpdateGuestProfileRequest,
    ): Mono<GuestSessionResponse> {
        return identityService.updateProfile(sessionToken, request)
    }
}
