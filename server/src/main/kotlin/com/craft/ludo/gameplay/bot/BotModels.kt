package com.craft.ludo.gameplay.bot

enum class BotDifficulty {
    EASY,
    MEDIUM,
    HARD,
    EXPERT,
    SUPER,
}

enum class GamePhase {
    EARLY,
    MID,
    LATE,
}

enum class OpponentStyle {
    AGGRESSIVE,
    SAFE,
    RUNNER,
    HUNTER,
    BALANCED,
}

/**
 * Tunable heuristic weights. Logic stays in the evaluator — change numbers here.
 */
data class BotRewardWeights(
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
    val twoPlayerAttackMultiplier: Double = 1.65,
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
    val expectimaxDepth: Int = 3,
    val maxDecisionMillis: Long = 50,
    val debugLogging: Boolean = false,
)

data class CandidateMove(
    val tokenIndex: Int,
    val fromProgress: Int,
    val toProgress: Int,
)

data class BoardScoreBreakdown(
    val progress: Double = 0.0,
    val safety: Double = 0.0,
    val capture: Double = 0.0,
    val hunt: Double = 0.0,
    val team: Double = 0.0,
    val balance: Double = 0.0,
    val territory: Double = 0.0,
    val pressure: Double = 0.0,
    val escape: Double = 0.0,
    val home: Double = 0.0,
    val risk: Double = 0.0,
    val future: Double = 0.0,
) {
    val total: Double
        get() = progress + safety + capture + hunt + team + balance +
            territory + pressure + escape + home + risk + future
}

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
    val boardScore: BoardScoreBreakdown = BoardScoreBreakdown(),
    val huntScore: Double = 0.0,
    val escapeScore: Double = 0.0,
    val phase: GamePhase = GamePhase.MID,
)
