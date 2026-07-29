package com.craft.ludo.shared.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app")
data class AppProperties(
    val session: SessionProperties = SessionProperties(),
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

data class GameplayProperties(
    val turnTimeoutSeconds: Long = 30,
    val maxConsecutiveMissedTurns: Int = 3,
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
    val expectimaxDepth: Int = 3,
    val maxDecisionMillis: Long = 50,
    val twoPlayerAttackMultiplier: Double = 1.65,
    val winGame: Double = 10_000.0,
    val tokenHome: Double = 1_400.0,
    val captureBase: Double = 2_800.0,
    val escapeThreat: Double = 700.0,
    val createBlockade: Double = 450.0,
    val breakOpponentBlockade: Double = 380.0,
    val enterHomePath: Double = 360.0,
    val landSafe: Double = 240.0,
    val saveThreatened: Double = 260.0,
    val createCaptureThreat: Double = 240.0,
    val maintainBlockade: Double = 160.0,
    val leaveBase: Double = 140.0,
    val progressPerStep: Double = 12.0,
    val exposeToCapture: Double = -850.0,
    val multiOpponentDanger: Double = -1_100.0,
    val breakOwnBlockade: Double = -280.0,
    val ignoreGuaranteedCapture: Double = -2_000.0,
    val leaveSafetyIntoDanger: Double = -320.0,
    val huntReward: Double = 780.0,
    val dangerProbabilityPenalty: Double = -1_100.0,
    val tokenDiversityReward: Double = 220.0,
    val huntHorizonTurns: Int = 3,
    val balanceReward: Double = 260.0,
    val territoryReward: Double = 180.0,
    val pressureReward: Double = 210.0,
    val escapeReward: Double = 720.0,
    val homeLaneFinishBonus: Double = 180.0,
    val captureNearHomeBonus: Double = 220.0,
    val captureAdvancedBonus: Double = 14.0,
    val lateProgressMultiplier: Double = 1.35,
    val debugLogging: Boolean = false,
)

data class WalletProperties(
    val currency: String = "INR",
    val guestStartingBalance: Long = 100_000,
    val payoutRakeBasisPoints: Int = 0,
    val platformFeePerPlayer: Long = 10,
    val houseUserId: String = "house",
)

data class OperatorProperties(
    val baseUrl: String = "https://sp.adminsportal.com",
    val loginPath: String = "/operator/user/login",
    val userDetailPath: String = "/service/user/detail",
    val balancePath: String = "/service/operator/user/balance/v2",
    /** Full URL for HTTP wallet credits (wins/refunds). */
    val creditUrl: String = "https://api.aakda.in/api/wallet/credit",
    val creditPath: String = "/api/wallet/credit",
    val creditExchange: String = "/games/admin",
    val creditQueueName: String = "games_cashout",
    val creditRoutingKey: String = "games_cashout",
    val gameId: Int = 2,
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
