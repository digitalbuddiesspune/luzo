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
    val maxDecisionMillis: Long = 45,
    val twoPlayerAttackMultiplier: Double = 1.30,
    val winGame: Double = 10_000.0,
    val tokenHome: Double = 1_200.0,
    val captureBase: Double = 800.0,
    val escapeThreat: Double = 500.0,
    val createBlockade: Double = 400.0,
    val breakOpponentBlockade: Double = 350.0,
    val enterHomePath: Double = 300.0,
    val landSafe: Double = 220.0,
    val saveThreatened: Double = 200.0,
    val createCaptureThreat: Double = 180.0,
    val maintainBlockade: Double = 150.0,
    val leaveBase: Double = 120.0,
    val progressPerStep: Double = 10.0,
    val exposeToCapture: Double = -700.0,
    val multiOpponentDanger: Double = -900.0,
    val breakOwnBlockade: Double = -300.0,
    val ignoreGuaranteedCapture: Double = -250.0,
    val leaveSafetyIntoDanger: Double = -250.0,
)

data class WalletProperties(
    val currency: String = "INR",
    val guestStartingBalance: Long = 100_000,
    val payoutRakeBasisPoints: Int = 0,
    val houseUserId: String = "house",
)

data class OperatorProperties(
    val baseUrl: String = "https://sp.adminsportal.com",
    val loginPath: String = "/operator/user/login",
    val userDetailPath: String = "/service/user/detail",
    val balancePath: String = "/service/operator/user/balance/v2",
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
