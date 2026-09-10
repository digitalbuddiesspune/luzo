package com.craft.ludo.shared.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app")
data class AppProperties(
    val session: SessionProperties = SessionProperties(),
    val sessionService: SessionServiceProperties = SessionServiceProperties(),
    val gameplay: GameplayProperties = GameplayProperties(),
    val wallet: WalletProperties = WalletProperties(),
    val operator: OperatorProperties = OperatorProperties(),
    val instance: InstanceProperties = InstanceProperties(),
    val realtime: RealtimeProperties = RealtimeProperties(),
    val web: WebProperties = WebProperties(),
)

data class SessionProperties(
    val ttlDays: Long = 30,
)

data class SessionServiceProperties(
    val enabled: Boolean = false,
    /** Provider API base — PROVIDER_API_URL / APP_SESSION_SERVICE_API_BASE_URL. */
    val apiBaseUrl: String = "https://api.dpbossking.com/api/v1",
    /** Required for in.oreng:game-sdk wallet adapter calls (debit/credit/balance). */
    val gameServerApiKey: String = "",
    val timeoutSeconds: Long = 5,
)

data class GameplayProperties(
    val turnTimeoutSeconds: Long = 30,
    val maxMissedTurns: Int = 2,
    val roomMaxPlayers: Int = 4,
    val onlineEntryFee: Long = 100,
    val lobbyWaitMillis: Long = 60_000,
    val onlinePvpRealPlayerThreshold: Int = 0,
    val rollDelayMillis: Long = 700,
    val botMoveDelayMillis: Long = 850,
    val advanceDelayMillis: Long = 750,
    val bot: BotProperties = BotProperties(),
)

data class BotProperties(
    val difficulty: String = "SUPER",
    val expectimaxDepth: Int = 2,
    val maxDecisionMillis: Long = 300,
    val twoPlayerAttackMultiplier: Double = 1.60,
    val winGame: Double = 10_000.0,
    val tokenHome: Double = 2_200.0,
    val captureBase: Double = 2_500.0,
    val escapeThreat: Double = 650.0,
    val createBlockade: Double = 400.0,
    val breakOpponentBlockade: Double = 350.0,
    val enterHomePath: Double = 500.0,
    val landSafe: Double = 220.0,
    val saveThreatened: Double = 280.0,
    val createCaptureThreat: Double = 220.0,
    val maintainBlockade: Double = 150.0,
    val leaveBase: Double = 120.0,
    val progressPerStep: Double = 12.0,
    val exposeToCapture: Double = -700.0,
    val multiOpponentDanger: Double = -900.0,
    val breakOwnBlockade: Double = -300.0,
    val ignoreGuaranteedCapture: Double = -2_000.0,
    val leaveSafetyIntoDanger: Double = -250.0,
    val huntReward: Double = 750.0,
    val dangerProbabilityPenalty: Double = -900.0,
    val tokenDiversityReward: Double = 180.0,
    val huntHorizonTurns: Int = 4,
)

data class WalletProperties(
    val currency: String = "INR",
    val guestStartingBalance: Long = 100_000,
    val payoutRakeBasisPoints: Int = 0,
    val platformFeePerPlayer: Long = 10,
    val houseUserId: String = "house",
)

data class OperatorProperties(
    /** Full URL for entry-fee debit HTTP API. */
    val debitUrl: String = "",
    /** Full URL for winner/refund credit HTTP API. */
    val creditUrl: String = "",
    /** Game name/code sent in wallet debit/credit requests. */
    val creditGameName: String = "POTLUDO",
    /** Legacy numeric game id (RabbitMQ / operator payloads). */
    val gameId: Int = 2,
    /** Platform game code from launch URL / session (e.g. POTLUDO). */
    val gameCode: String = "POTLUDO",
    /** Delayed exchange used to publish winner/refund cashout messages (legacy). */
    val creditExchange: String = "/games/admin",
    val creditQueueName: String = "games_cashout",
    val creditRoutingKey: String = "games_cashout",
    /** When true, declare delayed exchange + queue + DLQ (local/dev). Production usually leaves this false. */
    val creditDeclareTopology: Boolean = false,
    val creditDeadLetterExchange: String = "",
    val creditDeadLetterQueue: String = "games_cashout.dlq",
    val creditDeadLetterRoutingKey: String = "games_cashout.dlq",
    val creditMaxRetries: Int = 5,
    val creditRetryDelayMillis: Long = 5_000,
)

data class InstanceProperties(
    val id: String? = null,
    val lockKeyPrefix: String = "potludo:lock",
    val heartbeatKeyPrefix: String = "potludo:instance",
    val lockTtlMillis: Long = 5_000,
    val heartbeatTtlMillis: Long = 15_000,
)

data class RealtimeProperties(
    val webRtc: WebRtcProperties = WebRtcProperties(),
    val redisChannel: String = "potludo:realtime",
)

data class WebRtcProperties(
    val iceServers: List<String> = listOf("stun:stun.l.google.com:19302"),
    val turnUsername: String? = null,
    val turnCredential: String? = null,
)

data class WebProperties(
    val allowedOriginPatterns: List<String> = listOf(
        "http://localhost:3000",
        "http://127.0.0.1:3000",
        "http://localhost:3005",
        "http://127.0.0.1:3005",
    ),
)
