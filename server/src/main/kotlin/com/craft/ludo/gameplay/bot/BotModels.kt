package com.craft.ludo.gameplay.bot

enum class BotDifficulty {
    EASY,
    MEDIUM,
    HARD,
    EXPERT,
    SUPER,
}

data class BotRewardWeights(
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
    val twoPlayerAttackMultiplier: Double = 1.30,
    val expectimaxDepth: Int = 2,
    val maxDecisionMillis: Long = 45,
)

data class CandidateMove(
    val tokenIndex: Int,
    val fromProgress: Int,
    val toProgress: Int,
)

data class MoveEvaluation(
    val move: CandidateMove,
    val immediateReward: Double,
    val progressReward: Double,
    val safetyReward: Double,
    val attackReward: Double,
    val strategicReward: Double,
    val riskPenalty: Double,
    val futureValue: Double,
    val finalScore: Double,
    val reason: String,
)
