package com.craft.ludo.gameplay.bot

import com.craft.ludo.gameplay.FINISHED_PROGRESS
import com.craft.ludo.gameplay.HOME_LANE_LAST_PROGRESS
import com.craft.ludo.gameplay.HOME_LANE_START_PROGRESS
import com.craft.ludo.gameplay.MAIN_PATH_LAST_PROGRESS
import com.craft.ludo.gameplay.MatchPlayerState
import com.craft.ludo.gameplay.boardCellKey
import com.craft.ludo.gameplay.canMoveToken
import com.craft.ludo.gameplay.isEffectivelyAbandoned
import com.craft.ludo.gameplay.ludoSafeCellKeys
import org.slf4j.LoggerFactory
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Elite Ludo bot: board-level heuristic evaluation + shallow fair-dice expectimax.
 *
 * Dice are never manipulated. Strength comes only from choosing among legal moves
 * after a fair roll. "Look-ahead" averages over the uniform 1..6 distribution.
 */
object SuperiorBotEngine {
    private val log = LoggerFactory.getLogger(SuperiorBotEngine::class.java)

    @Volatile
    var weights: BotRewardWeights = BotRewardWeights()

    @Volatile
    var difficulty: BotDifficulty = BotDifficulty.SUPER

    fun chooseToken(
        players: List<MatchPlayerState>,
        playerIndex: Int,
        movableTokenIndexes: List<Int>,
        diceValue: Int,
        difficultyOverride: BotDifficulty? = null,
        weightsOverride: BotRewardWeights? = null,
        random: Random = Random.Default,
    ): Int {
        if (movableTokenIndexes.isEmpty()) return 0
        if (movableTokenIndexes.size == 1) return movableTokenIndexes.first()

        val activeDifficulty = difficultyOverride ?: difficulty
        val activeWeights = weightsOverride ?: weights
        val startedAt = System.nanoTime()
        val deadlineNs = startedAt + activeWeights.maxDecisionMillis * 1_000_000L

        val evaluations = when (activeDifficulty) {
            BotDifficulty.EASY -> evaluateEasy(players, playerIndex, movableTokenIndexes, diceValue, activeWeights, random)
            BotDifficulty.MEDIUM -> evaluateAllMoves(
                players = players,
                playerIndex = playerIndex,
                movableTokenIndexes = movableTokenIndexes,
                diceValue = diceValue,
                weights = activeWeights,
                useExpectimax = false,
                deadlineNs = deadlineNs,
            )
            BotDifficulty.HARD,
            BotDifficulty.EXPERT,
            BotDifficulty.SUPER,
            -> evaluateAllMoves(
                players = players,
                playerIndex = playerIndex,
                movableTokenIndexes = movableTokenIndexes,
                diceValue = diceValue,
                weights = activeWeights,
                useExpectimax = activeDifficulty != BotDifficulty.HARD,
                deadlineNs = deadlineNs,
            )
        }

        val best = selectBestMove(players, playerIndex, diceValue, evaluations, random)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        if (activeWeights.debugLogging || log.isDebugEnabled) {
            logDecision(players[playerIndex].displayName, diceValue, activeDifficulty, best, evaluations, elapsedMs)
        }

        return best.move.tokenIndex
    }

    /** Instant match-win is the only hard override; everything else is total EV. */
    private fun selectBestMove(
        players: List<MatchPlayerState>,
        playerIndex: Int,
        diceValue: Int,
        evaluations: List<MoveEvaluation>,
        random: Random,
    ): MoveEvaluation {
        val winning = evaluations.filter { evaluation ->
            val after = simulateMove(players, playerIndex, evaluation.move, diceValue)
            tokensAllFinished(after[playerIndex])
        }
        if (winning.isNotEmpty()) {
            return tieBreak(winning, random)
        }
        return tieBreak(evaluations, random)
    }

    private fun evaluateEasy(
        players: List<MatchPlayerState>,
        playerIndex: Int,
        movableTokenIndexes: List<Int>,
        diceValue: Int,
        weights: BotRewardWeights,
        random: Random,
    ): List<MoveEvaluation> {
        return movableTokenIndexes.map { tokenIndex ->
            val move = candidateMove(players[playerIndex], tokenIndex, diceValue)
            val progress = BoardEvaluator.progressDelta(move.fromProgress, move.toProgress, weights, GamePhase.EARLY)
            MoveEvaluation(
                move = move,
                immediateReward = progress,
                progressReward = progress,
                safetyReward = 0.0,
                attackReward = 0.0,
                strategicReward = 0.0,
                riskPenalty = 0.0,
                futureValue = random.nextDouble() * 5.0,
                finalScore = progress + random.nextDouble() * 5.0,
                reason = "Easy random-biased progress",
                phase = GamePhase.EARLY,
            )
        }
    }

    private fun evaluateAllMoves(
        players: List<MatchPlayerState>,
        playerIndex: Int,
        movableTokenIndexes: List<Int>,
        diceValue: Int,
        weights: BotRewardWeights,
        useExpectimax: Boolean,
        deadlineNs: Long,
    ): List<MoveEvaluation> {
        val phase = BoardEvaluator.detectPhase(players, playerIndex)
        val styles = BoardEvaluator.classifyOpponents(players, playerIndex)
        val threatByOpponent = players.indices.map { index ->
            if (index == playerIndex || players[index].isEffectivelyAbandoned()) {
                0.0
            } else {
                BoardEvaluator.opponentThreat(players, playerIndex, index)
            }
        }
        val beforeBoard = BoardEvaluator.scoreBoard(
            players = players,
            botIndex = playerIndex,
            weights = weights,
            phase = phase,
            styles = styles,
            threatByOpponent = threatByOpponent,
            movedTokenIndex = null,
            previousPlayers = null,
        )

        return movableTokenIndexes.map { tokenIndex ->
            val move = candidateMove(players[playerIndex], tokenIndex, diceValue)
            val after = simulateMove(players, playerIndex, move, diceValue)
            val afterBoard = BoardEvaluator.scoreBoard(
                players = after,
                botIndex = playerIndex,
                weights = weights,
                phase = phase,
                styles = styles,
                threatByOpponent = threatByOpponent,
                movedTokenIndex = move.tokenIndex,
                previousPlayers = players,
            )

            val developBonus = BoardEvaluator.trailingDevelopBonus(
                before = players[playerIndex],
                move = move,
                weights = weights,
                phase = phase,
            )

            val captureDelta = afterBoard.capture - beforeBoard.capture
            val huntDelta = afterBoard.hunt - beforeBoard.hunt
            val escapeDelta = afterBoard.escape - beforeBoard.escape
            val progressDelta = afterBoard.progress - beforeBoard.progress
            val safetyDelta = afterBoard.safety - beforeBoard.safety
            val teamDelta = afterBoard.team + afterBoard.balance + afterBoard.territory + afterBoard.pressure -
                (beforeBoard.team + beforeBoard.balance + beforeBoard.territory + beforeBoard.pressure) +
                developBonus
            val homeDelta = afterBoard.home - beforeBoard.home
            val riskDelta = afterBoard.risk - beforeBoard.risk

            var future = 0.0
            if (
                useExpectimax &&
                System.nanoTime() < deadlineNs &&
                weights.expectimaxDepth >= 2
            ) {
                val lookAheadValue = Expectimax.search(
                    resultingPlayers = after,
                    botIndex = playerIndex,
                    weights = weights,
                    depth = weights.expectimaxDepth.coerceIn(2, 3),
                    deadlineNs = deadlineNs,
                )
                // Blend only a fraction of the look-ahead delta so strong
                // immediate EV (captures, finishes) is not erased by opponent replies.
                future = (lookAheadValue - afterBoard.total) * 0.18
            }

            val breakdown = BoardScoreBreakdown(
                progress = progressDelta,
                safety = safetyDelta,
                capture = captureDelta,
                hunt = huntDelta,
                team = teamDelta,
                balance = afterBoard.balance - beforeBoard.balance,
                territory = afterBoard.territory - beforeBoard.territory,
                pressure = afterBoard.pressure - beforeBoard.pressure,
                escape = escapeDelta,
                home = homeDelta,
                risk = riskDelta,
                future = future,
            )

            val winBonus = if (tokensAllFinished(after[playerIndex])) weights.winGame else 0.0
            val finalScore = winBonus + breakdown.total

            MoveEvaluation(
                move = move,
                immediateReward = winBonus + homeDelta,
                progressReward = progressDelta,
                safetyReward = safetyDelta + escapeDelta,
                attackReward = captureDelta + huntDelta,
                strategicReward = teamDelta,
                riskPenalty = riskDelta,
                futureValue = future,
                finalScore = finalScore,
                reason = summarizeReason(breakdown, winBonus),
                boardScore = breakdown,
                huntScore = huntDelta,
                escapeScore = escapeDelta,
                phase = phase,
            )
        }
    }

    internal fun simulateMove(
        players: List<MatchPlayerState>,
        playerIndex: Int,
        move: CandidateMove,
        diceValue: Int,
    ): List<MatchPlayerState> {
        val mutable = players.map { it.copy(tokens = it.tokens.toMutableList()) }.toMutableList()
        val active = mutable[playerIndex]
        val tokens = active.tokens.toMutableList()
        tokens[move.tokenIndex] = move.toProgress
        mutable[playerIndex] = active.copy(tokens = tokens)

        if (move.toProgress in 0..MAIN_PATH_LAST_PROGRESS) {
            val landingKey = boardCellKey(active.color, move.toProgress, move.tokenIndex)
            if (!ludoSafeCellKeys.contains(landingKey)) {
                mutable.forEachIndexed { opponentIndex, opponent ->
                    if (opponentIndex == playerIndex || opponent.isEffectivelyAbandoned()) {
                        return@forEachIndexed
                    }
                    val adjusted = opponent.tokens.mapIndexed { tokenIndex, progress ->
                        if (
                            progress in 0..MAIN_PATH_LAST_PROGRESS &&
                            boardCellKey(opponent.color, progress, tokenIndex) == landingKey
                        ) {
                            -1
                        } else {
                            progress
                        }
                    }
                    mutable[opponentIndex] = opponent.copy(tokens = adjusted)
                }
            }
        }

        return mutable.map { it.copy(tokens = it.tokens.toList()) }
    }

    private fun candidateMove(player: MatchPlayerState, tokenIndex: Int, diceValue: Int): CandidateMove {
        val from = player.tokens[tokenIndex]
        val to = if (from == -1) 0 else from + diceValue
        return CandidateMove(tokenIndex = tokenIndex, fromProgress = from, toProgress = to)
    }

    private fun tokensAllFinished(player: MatchPlayerState): Boolean =
        player.tokens.isNotEmpty() && player.tokens.all { it == FINISHED_PROGRESS }

    private fun summarizeReason(score: BoardScoreBreakdown, winBonus: Double): String {
        if (winBonus >= 9_000) return "Winning move"
        val parts = linkedMapOf(
            "capture" to score.capture,
            "escape" to score.escape,
            "home" to score.home,
            "hunt" to score.hunt,
            "safety" to score.safety,
            "progress" to score.progress,
            "balance" to score.balance,
            "territory" to score.territory,
            "pressure" to score.pressure,
            "future" to score.future,
            "risk" to score.risk,
        )
        val top = parts.maxByOrNull { abs(it.value) } ?: return "Balanced board EV"
        return when {
            top.key == "capture" && top.value >= 500 -> "High-EV capture"
            top.key == "escape" && top.value >= 200 -> "Escape danger"
            top.key == "home" && top.value >= 200 -> "Home / finish"
            top.key == "hunt" && top.value >= 150 -> "Intelligent hunt setup"
            top.key == "balance" && top.value >= 80 -> "Token balance"
            top.key == "future" -> "Better expected future"
            else -> "Best board EV (${top.key})"
        }
    }

    private fun tieBreak(evaluations: List<MoveEvaluation>, random: Random): MoveEvaluation {
        val bestScore = evaluations.maxOf { it.finalScore }
        val tied = evaluations.filter { abs(it.finalScore - bestScore) < 1e-6 }
        return tied[random.nextInt(tied.size)]
    }

    private fun logDecision(
        playerName: String,
        diceValue: Int,
        difficulty: BotDifficulty,
        best: MoveEvaluation,
        evaluations: List<MoveEvaluation>,
        elapsedMs: Long,
    ) {
        log.info(
            "BOT_DECISION player={} dice={} difficulty={} phase={} chosen=T{} final={} reason=\"{}\" ms={}",
            playerName,
            diceValue,
            difficulty,
            best.phase,
            best.move.tokenIndex + 1,
            best.finalScore.roundToInt(),
            best.reason,
            elapsedMs,
        )
        evaluations.sortedByDescending { it.finalScore }.forEach { evaluation ->
            val s = evaluation.boardScore
            log.info(
                "  candidate=T{} {}→{} progress={} safety={} capture={} hunt={} risk={} escape={} board={} future={} final={}",
                evaluation.move.tokenIndex + 1,
                evaluation.move.fromProgress,
                evaluation.move.toProgress,
                s.progress.roundToInt(),
                s.safety.roundToInt(),
                s.capture.roundToInt(),
                s.hunt.roundToInt(),
                s.risk.roundToInt(),
                s.escape.roundToInt(),
                s.total.roundToInt(),
                s.future.roundToInt(),
                evaluation.finalScore.roundToInt(),
            )
        }
    }
}

/**
 * Full-board scorer. Never mutates live state.
 */
internal object BoardEvaluator {
    fun detectPhase(players: List<MatchPlayerState>, botIndex: Int): GamePhase {
        val bot = players[botIndex]
        val finished = bot.tokens.count { it == FINISHED_PROGRESS }
        val outside = bot.tokens.filter { it in 0..MAIN_PATH_LAST_PROGRESS }
        val avg = if (outside.isEmpty()) {
            if (finished > 0) FINISHED_PROGRESS.toDouble() else 0.0
        } else {
            outside.average()
        }
        val homeLane = bot.tokens.count { it in HOME_LANE_START_PROGRESS until FINISHED_PROGRESS }
        return when {
            finished >= 2 || homeLane >= 2 || avg >= 38 -> GamePhase.LATE
            outside.size >= 2 && avg >= 16 -> GamePhase.MID
            else -> GamePhase.EARLY
        }
    }

    fun classifyOpponents(
        players: List<MatchPlayerState>,
        botIndex: Int,
    ): Map<Int, OpponentStyle> {
        val styles = HashMap<Int, OpponentStyle>(players.size)
        players.forEachIndexed { index, player ->
            if (index == botIndex || player.isEffectivelyAbandoned()) return@forEachIndexed
            styles[index] = classifyOpponent(player)
        }
        return styles
    }

    private fun classifyOpponent(player: MatchPlayerState): OpponentStyle {
        val outside = player.tokens.filter { it in 0..MAIN_PATH_LAST_PROGRESS }
        if (outside.isEmpty()) return OpponentStyle.BALANCED
        val avg = outside.average()
        val spread = (outside.maxOrNull() ?: 0) - (outside.minOrNull() ?: 0)
        val advanced = outside.count { it >= 35 }
        val clustered = outside.size >= 2 && spread <= 8
        return when {
            advanced >= 1 && outside.size == 1 -> OpponentStyle.RUNNER
            advanced >= 1 && clustered -> OpponentStyle.AGGRESSIVE
            outside.size >= 3 && avg < 20 -> OpponentStyle.SAFE
            advanced >= 1 && spread >= 18 -> OpponentStyle.HUNTER
            else -> OpponentStyle.BALANCED
        }
    }

    fun scoreBoard(
        players: List<MatchPlayerState>,
        botIndex: Int,
        weights: BotRewardWeights,
        phase: GamePhase,
        styles: Map<Int, OpponentStyle>,
        threatByOpponent: List<Double>,
        movedTokenIndex: Int?,
        previousPlayers: List<MatchPlayerState>?,
    ): BoardScoreBreakdown {
        val bot = players[botIndex]
        if (tokensAllFinished(bot)) {
            return BoardScoreBreakdown(home = weights.winGame)
        }

        val progress = scoreProgress(bot, weights, phase)
        val safety = scoreSafety(players, botIndex, weights)
        val capture = scoreCaptureDelta(players, botIndex, weights, previousPlayers, styles)
        val hunt = scoreHunt(players, botIndex, weights, threatByOpponent, styles)
        val team = scoreTeamPlay(bot, weights, phase)
        val balance = scoreBalance(bot, weights, phase)
        val territory = scoreTerritory(players, botIndex, weights)
        val pressure = scorePressure(players, botIndex, weights, styles)
        val escape = scoreEscape(players, botIndex, weights, previousPlayers, movedTokenIndex)
        val home = scoreHome(bot, weights)
        val risk = scoreRisk(players, botIndex, weights)

        return BoardScoreBreakdown(
            progress = progress,
            safety = safety,
            capture = capture,
            hunt = hunt,
            team = team,
            balance = balance,
            territory = territory,
            pressure = pressure,
            escape = escape,
            home = home,
            risk = risk,
        )
    }

    fun progressDelta(from: Int, to: Int, weights: BotRewardWeights, phase: GamePhase): Double {
        if (to < 0 || to > FINISHED_PROGRESS) return 0.0
        val steps = if (from < 0) 1 else max(0, to - from)
        val ratio = to.toDouble() / FINISHED_PROGRESS
        val curve = when {
            ratio < 0.25 -> 1.0
            ratio < 0.50 -> 1.2
            ratio < 0.75 -> 1.55
            ratio < 0.90 -> 2.1
            else -> 3.2
        }
        val phaseMul = when (phase) {
            GamePhase.EARLY -> 0.95
            GamePhase.MID -> 1.05
            GamePhase.LATE -> weights.lateProgressMultiplier
        }
        return steps * weights.progressPerStep * curve * phaseMul
    }

    /**
     * Explicitly rewards advancing the lagging outside token so the bot does not
     * tunnel on a single runner unless that runner is close to finishing.
     */
    fun trailingDevelopBonus(
        before: MatchPlayerState,
        move: CandidateMove,
        weights: BotRewardWeights,
        phase: GamePhase,
    ): Double {
        val outside = before.tokens.withIndex()
            .filter { it.value in 0..MAIN_PATH_LAST_PROGRESS }
        if (outside.size < 2) return 0.0
        val trailing = outside.minBy { it.value }
        val leading = outside.maxBy { it.value }
        val spread = leading.value - trailing.value
        if (move.tokenIndex == trailing.index && spread >= 8) {
            val phaseMul = when (phase) {
                GamePhase.EARLY -> 1.25
                GamePhase.MID -> 1.1
                GamePhase.LATE -> 0.55
            }
            return weights.balanceReward * (spread.coerceAtMost(28) / 28.0) * 1.35 * phaseMul
        }
        if (
            move.tokenIndex == leading.index &&
            spread >= 16 &&
            leading.value < HOME_LANE_START_PROGRESS - 4 &&
            phase != GamePhase.LATE
        ) {
            return -weights.balanceReward * 0.55
        }
        return 0.0
    }

    private fun scoreProgress(bot: MatchPlayerState, weights: BotRewardWeights, phase: GamePhase): Double {
        var total = 0.0
        val phaseMul = when (phase) {
            GamePhase.EARLY -> 0.95
            GamePhase.MID -> 1.05
            GamePhase.LATE -> weights.lateProgressMultiplier
        }
        // Continuity: entering home must never score below sitting on the last main-path cell.
        val mainPathCapValue = MAIN_PATH_LAST_PROGRESS * weights.progressPerStep * 0.35 * 2.1 * phaseMul
        bot.tokens.forEach { progress ->
            when {
                progress == FINISHED_PROGRESS -> total += weights.tokenHome + mainPathCapValue
                progress in HOME_LANE_START_PROGRESS..HOME_LANE_LAST_PROGRESS -> {
                    total += mainPathCapValue + weights.enterHomePath +
                        (progress - HOME_LANE_START_PROGRESS + 1) * weights.progressPerStep * 2.4
                }
                progress >= 0 -> {
                    val ratio = progress.toDouble() / FINISHED_PROGRESS
                    val curve = 1.0 + ratio * 2.2
                    total += progress * weights.progressPerStep * 0.35 * curve * phaseMul
                }
                else -> total -= weights.leaveBase * 0.15
            }
        }
        val outside = bot.tokens.count { it in 0..MAIN_PATH_LAST_PROGRESS }
        if (phase == GamePhase.EARLY && outside in 2..3) {
            total += weights.leaveBase * 0.8
        }
        return total
    }

    private fun scoreSafety(
        players: List<MatchPlayerState>,
        botIndex: Int,
        weights: BotRewardWeights,
    ): Double {
        var total = 0.0
        val bot = players[botIndex]
        bot.tokens.forEachIndexed { tokenIndex, progress ->
            when {
                progress in HOME_LANE_START_PROGRESS..FINISHED_PROGRESS -> {
                    total += weights.landSafe * 0.6
                }
                progress in 0..MAIN_PATH_LAST_PROGRESS -> {
                    val key = boardCellKey(bot.color, progress, tokenIndex)
                    if (ludoSafeCellKeys.contains(key)) {
                        total += weights.landSafe
                    } else {
                        val p1 = captureProbabilityWithinTurns(players, botIndex, key, 1)
                        val p2 = captureProbabilityWithinTurns(players, botIndex, key, 2)
                        val progressMul = 1.0 + progress.toDouble() / FINISHED_PROGRESS
                        total += weights.dangerProbabilityPenalty * (p1 * 1.35 + p2 * 0.65) * progressMul * 0.45
                        val attackers = countAttackers(players, botIndex, key)
                        if (attackers >= 2) total += weights.multiOpponentDanger * 0.35
                        else if (attackers == 1) total += weights.exposeToCapture * 0.25
                    }
                }
            }
        }
        return total
    }

    private fun scoreCaptureDelta(
        players: List<MatchPlayerState>,
        botIndex: Int,
        weights: BotRewardWeights,
        previousPlayers: List<MatchPlayerState>?,
        styles: Map<Int, OpponentStyle>,
    ): Double {
        if (previousPlayers == null) return 0.0
        var total = 0.0
        val twoPlayer = players.count { !it.isEffectivelyAbandoned() } == 2
        previousPlayers.forEachIndexed { opponentIndex, beforeOpponent ->
            if (opponentIndex == botIndex || beforeOpponent.isEffectivelyAbandoned()) return@forEachIndexed
            val afterOpponent = players[opponentIndex]
            beforeOpponent.tokens.forEachIndexed { tokenIndex, beforeProgress ->
                val afterProgress = afterOpponent.tokens.getOrElse(tokenIndex) { beforeProgress }
                if (beforeProgress in 0..MAIN_PATH_LAST_PROGRESS && afterProgress == -1) {
                    var value = weights.captureBase +
                        beforeProgress * weights.captureAdvancedBonus +
                        if (beforeProgress >= 40) weights.captureNearHomeBonus * 1.35
                        else if (beforeProgress >= 25) 120.0
                        else 0.0
                    // Capturing an advanced token should beat a routine home-lane entry.
                    if (beforeProgress >= 40) {
                        value += weights.enterHomePath * 1.1
                    }
                    val style = styles[opponentIndex] ?: OpponentStyle.BALANCED
                    value *= when (style) {
                        OpponentStyle.AGGRESSIVE, OpponentStyle.HUNTER -> 1.12
                        OpponentStyle.RUNNER -> 1.18
                        OpponentStyle.SAFE -> 0.92
                        OpponentStyle.BALANCED -> 1.0
                    }
                    if (twoPlayer) value *= weights.twoPlayerAttackMultiplier

                    // Post-capture vulnerability of the capturing token (approx: any newly advanced bot token at risk).
                    val bot = players[botIndex]
                    bot.tokens.forEachIndexed { movedIndex, progress ->
                        if (progress !in 0..MAIN_PATH_LAST_PROGRESS) return@forEachIndexed
                        val key = boardCellKey(bot.color, progress, movedIndex)
                        if (ludoSafeCellKeys.contains(key)) return@forEachIndexed
                        val deathProb = captureProbabilityWithinTurns(players, botIndex, key, 1)
                        value -= abs(weights.dangerProbabilityPenalty) * deathProb * 0.85 *
                            (1.0 + progress.toDouble() / FINISHED_PROGRESS)
                    }
                    total += value
                }
            }
        }
        return total
    }

    private fun scoreHunt(
        players: List<MatchPlayerState>,
        botIndex: Int,
        weights: BotRewardWeights,
        threatByOpponent: List<Double>,
        styles: Map<Int, OpponentStyle>,
    ): Double {
        val bot = players[botIndex]
        val horizon = weights.huntHorizonTurns.coerceIn(1, 4)
        var best = 0.0
        var total = 0.0
        bot.tokens.forEachIndexed { tokenIndex, fromProgress ->
            if (fromProgress !in 0..MAIN_PATH_LAST_PROGRESS) return@forEachIndexed
            players.forEachIndexed { opponentIndex, opponent ->
                if (opponentIndex == botIndex || opponent.isEffectivelyAbandoned()) return@forEachIndexed
                val styleMul = when (styles[opponentIndex] ?: OpponentStyle.BALANCED) {
                    OpponentStyle.RUNNER -> 1.2
                    OpponentStyle.AGGRESSIVE -> 1.1
                    OpponentStyle.HUNTER -> 1.05
                    OpponentStyle.SAFE -> 0.85
                    OpponentStyle.BALANCED -> 1.0
                }
                opponent.tokens.forEachIndexed { oppToken, oppProgress ->
                    if (oppProgress !in 0..MAIN_PATH_LAST_PROGRESS) return@forEachIndexed
                    val targetKey = boardCellKey(opponent.color, oppProgress, oppToken)
                    if (ludoSafeCellKeys.contains(targetKey)) return@forEachIndexed
                    val distance = forwardDistanceToCell(
                        color = bot.color,
                        tokenIndex = tokenIndex,
                        fromProgress = fromProgress,
                        targetKey = targetKey,
                        maxDistance = horizon * 6,
                    ) ?: return@forEachIndexed
                    if (distance == 0) return@forEachIndexed
                    val reachProb = exactReachProbability(distance, horizon)
                    val escapeProb = min(0.75, distance / 12.0)
                    val targetValue = 1.0 +
                        oppProgress.toDouble() / FINISHED_PROGRESS * 1.6 +
                        threatByOpponent.getOrElse(opponentIndex) { 0.0 } * 0.07 +
                        if (oppProgress >= 40) 0.55 else 0.0
                    val huntValue = weights.huntReward * reachProb * (1.0 - escapeProb * 0.35) * targetValue * styleMul
                    best = max(best, huntValue)
                    total += huntValue * 0.35
                }
            }
        }
        return best * 0.65 + total * 0.35
    }

    private fun scoreTeamPlay(bot: MatchPlayerState, weights: BotRewardWeights, phase: GamePhase): Double {
        val outside = bot.tokens.count { it in 0..MAIN_PATH_LAST_PROGRESS }
        val inBase = bot.tokens.count { it < 0 }
        var score = 0.0
        when (phase) {
            GamePhase.EARLY -> {
                if (outside in 2..3) score += weights.tokenDiversityReward
                if (outside == 1 && inBase >= 2) score -= weights.tokenDiversityReward * 0.5
            }
            GamePhase.MID -> {
                if (outside >= 2) score += weights.tokenDiversityReward * 0.55
                score += ownStacks(bot) * weights.createBlockade * 0.35
            }
            GamePhase.LATE -> {
                val finishing = bot.tokens.count { it >= HOME_LANE_START_PROGRESS }
                score += finishing * weights.homeLaneFinishBonus * 0.25
            }
        }
        return score
    }

    private fun scoreBalance(bot: MatchPlayerState, weights: BotRewardWeights, phase: GamePhase): Double {
        val outside = bot.tokens.filter { it in 0..MAIN_PATH_LAST_PROGRESS }
        if (outside.size < 2) {
            return if (phase == GamePhase.EARLY && bot.tokens.any { it < 0 }) weights.balanceReward * 0.2 else 0.0
        }
        val nearHome = outside.count { it >= HOME_LANE_START_PROGRESS - 6 }
        if (nearHome > 0 && phase == GamePhase.LATE) {
            // Allow a finisher to pull ahead late.
            return weights.balanceReward * 0.15
        }
        val mean = outside.average()
        val variance = outside.map { (it - mean) * (it - mean) }.average()
        val std = sqrt(variance)
        // Prefer ~balanced spreads; heavy single-leader penalty.
        val balance = (22.0 - std).coerceIn(-18.0, 22.0) / 22.0
        return weights.balanceReward * balance
    }

    private fun scoreTerritory(
        players: List<MatchPlayerState>,
        botIndex: Int,
        weights: BotRewardWeights,
    ): Double {
        var score = 0.0
        val bot = players[botIndex]
        val stacks = ownStacks(bot)
        score += stacks * weights.createBlockade * 0.55
        score += stacks * weights.maintainBlockade * 0.25

        bot.tokens.forEachIndexed { tokenIndex, progress ->
            if (progress !in 0..MAIN_PATH_LAST_PROGRESS) return@forEachIndexed
            val key = boardCellKey(bot.color, progress, tokenIndex)
            if (ludoSafeCellKeys.contains(key)) {
                score += weights.territoryReward * 0.12
            }
            // Occupying cells that opponents must pass soon.
            players.forEachIndexed { opponentIndex, opponent ->
                if (opponentIndex == botIndex || opponent.isEffectivelyAbandoned()) return@forEachIndexed
                opponent.tokens.forEachIndexed { oppToken, oppProgress ->
                    if (oppProgress !in 0..MAIN_PATH_LAST_PROGRESS) return@forEachIndexed
                    val distance = forwardDistanceToCell(
                        color = opponent.color,
                        tokenIndex = oppToken,
                        fromProgress = oppProgress,
                        targetKey = key,
                        maxDistance = 6,
                    )
                    if (distance != null && distance in 1..6) {
                        score += weights.territoryReward * 0.08
                    }
                }
            }
        }
        return score
    }

    private fun scorePressure(
        players: List<MatchPlayerState>,
        botIndex: Int,
        weights: BotRewardWeights,
        styles: Map<Int, OpponentStyle>,
    ): Double {
        var score = 0.0
        val bot = players[botIndex]
        players.forEachIndexed { opponentIndex, opponent ->
            if (opponentIndex == botIndex || opponent.isEffectivelyAbandoned()) return@forEachIndexed
            val advanced = opponent.tokens.filter { it in 35..MAIN_PATH_LAST_PROGRESS }
            if (advanced.isEmpty()) return@forEachIndexed
            val styleBoost = when (styles[opponentIndex]) {
                OpponentStyle.RUNNER, OpponentStyle.AGGRESSIVE -> 1.2
                else -> 1.0
            }
            advanced.forEach { oppProgress ->
                val pressureNear = bot.tokens.any { botProgress ->
                    botProgress in 0..MAIN_PATH_LAST_PROGRESS &&
                        abs(botProgress - oppProgress) <= 10
                }
                if (pressureNear) {
                    score += weights.pressureReward * 0.45 * styleBoost
                } else {
                    score -= weights.pressureReward * 0.12 * styleBoost
                }
            }
        }
        return score
    }

    private fun scoreEscape(
        players: List<MatchPlayerState>,
        botIndex: Int,
        weights: BotRewardWeights,
        previousPlayers: List<MatchPlayerState>?,
        movedTokenIndex: Int?,
    ): Double {
        if (previousPlayers == null || movedTokenIndex == null) return 0.0
        val before = previousPlayers[botIndex]
        val after = players[botIndex]
        val from = before.tokens.getOrElse(movedTokenIndex) { return 0.0 }
        val to = after.tokens.getOrElse(movedTokenIndex) { return 0.0 }
        if (from !in 0..MAIN_PATH_LAST_PROGRESS) return 0.0

        val fromKey = boardCellKey(before.color, from, movedTokenIndex)
        if (ludoSafeCellKeys.contains(fromKey)) return 0.0
        val beforeDanger = captureProbabilityWithinTurns(previousPlayers, botIndex, fromKey, 1)
        if (beforeDanger < 0.08) return 0.0

        val afterDanger = when {
            to in HOME_LANE_START_PROGRESS..FINISHED_PROGRESS -> 0.0
            to in 0..MAIN_PATH_LAST_PROGRESS -> {
                val toKey = boardCellKey(after.color, to, movedTokenIndex)
                if (ludoSafeCellKeys.contains(toKey)) 0.0
                else captureProbabilityWithinTurns(players, botIndex, toKey, 1)
            }
            else -> beforeDanger
        }
        val saved = (beforeDanger - afterDanger).coerceAtLeast(0.0)
        val lossAvoided = from.toDouble() / FINISHED_PROGRESS
        return weights.escapeReward * saved * (1.0 + lossAvoided) +
            if (saved > 0.2) weights.escapeThreat * 0.35 else 0.0
    }

    private fun scoreHome(bot: MatchPlayerState, weights: BotRewardWeights): Double {
        var score = 0.0
        bot.tokens.forEach { progress ->
            when {
                progress == FINISHED_PROGRESS -> score += weights.tokenHome + weights.homeLaneFinishBonus
                progress in HOME_LANE_START_PROGRESS..HOME_LANE_LAST_PROGRESS -> {
                    score += weights.enterHomePath +
                        (progress - HOME_LANE_START_PROGRESS + 1) * weights.homeLaneFinishBonus * 0.2
                }
            }
        }
        return score
    }

    private fun scoreRisk(
        players: List<MatchPlayerState>,
        botIndex: Int,
        weights: BotRewardWeights,
    ): Double {
        var risk = 0.0
        val bot = players[botIndex]
        bot.tokens.forEachIndexed { tokenIndex, progress ->
            if (progress !in 0..MAIN_PATH_LAST_PROGRESS) return@forEachIndexed
            val key = boardCellKey(bot.color, progress, tokenIndex)
            if (ludoSafeCellKeys.contains(key)) return@forEachIndexed
            val deathProb = captureProbabilityWithinTurns(players, botIndex, key, 2)
            val expectedLoss = progress * weights.progressPerStep * 0.5 +
                if (progress >= 40) weights.tokenHome * 0.25 else 0.0
            risk += weights.dangerProbabilityPenalty * deathProb * (1.0 + progress.toDouble() / FINISHED_PROGRESS) * 0.55 -
                expectedLoss * deathProb * 0.08
        }
        return risk
    }

    fun opponentThreat(
        players: List<MatchPlayerState>,
        botIndex: Int,
        opponentIndex: Int,
    ): Double {
        val opponent = players[opponentIndex]
        val completed = opponent.tokens.count { it == FINISHED_PROGRESS }
        val avgProgress = opponent.tokens.filter { it >= 0 }.map { it.coerceAtMost(FINISHED_PROGRESS) }
            .average()
            .takeIf { !it.isNaN() } ?: 0.0
        val nearHome = opponent.tokens.count { it >= 40 }
        val directThreat = opponent.tokens.count { progress ->
            if (progress !in 0..MAIN_PATH_LAST_PROGRESS) {
                false
            } else {
                players[botIndex].tokens.any { botProgress ->
                    botProgress in 0..MAIN_PATH_LAST_PROGRESS &&
                        (1..6).any { dice ->
                            canMoveToken(progress, dice) &&
                                boardCellKey(opponent.color, progress + dice, 0) ==
                                boardCellKey(players[botIndex].color, botProgress, 0)
                        }
                }
            }
        }
        return completed * 3.0 + avgProgress / 20.0 + nearHome * 1.5 + directThreat * 2.0
    }

    fun boardValue(players: List<MatchPlayerState>, botIndex: Int, weights: BotRewardWeights): Double {
        val phase = detectPhase(players, botIndex)
        val styles = classifyOpponents(players, botIndex)
        val threats = players.indices.map { index ->
            if (index == botIndex || players[index].isEffectivelyAbandoned()) 0.0
            else opponentThreat(players, botIndex, index)
        }
        return scoreBoard(players, botIndex, weights, phase, styles, threats, null, null).total
    }

    private fun tokensAllFinished(player: MatchPlayerState): Boolean =
        player.tokens.isNotEmpty() && player.tokens.all { it == FINISHED_PROGRESS }

    private fun ownStacks(player: MatchPlayerState): Int {
        val counts = HashMap<String, Int>(4)
        player.tokens.forEachIndexed { tokenIndex, progress ->
            if (progress in 0..MAIN_PATH_LAST_PROGRESS) {
                val key = boardCellKey(player.color, progress, tokenIndex)
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        return counts.values.count { it >= 2 }
    }

    fun forwardDistanceToCell(
        color: String,
        tokenIndex: Int,
        fromProgress: Int,
        targetKey: String,
        maxDistance: Int,
    ): Int? {
        if (fromProgress !in 0..MAIN_PATH_LAST_PROGRESS) return null
        val allowedDistance = min(maxDistance, MAIN_PATH_LAST_PROGRESS - fromProgress)
        for (distance in 1..allowedDistance) {
            if (boardCellKey(color, fromProgress + distance, tokenIndex) == targetKey) {
                return distance
            }
        }
        return null
    }

    fun exactReachProbability(distance: Int, horizonTurns: Int): Double {
        if (distance <= 0) return 0.0
        var distribution = doubleArrayOf(1.0)
        var probability = 0.0
        repeat(horizonTurns) {
            val next = DoubleArray(distribution.size + 6)
            distribution.forEachIndexed { sum, chance ->
                if (chance == 0.0) return@forEachIndexed
                for (dice in 1..6) {
                    next[sum + dice] += chance / 6.0
                }
            }
            distribution = next
            probability += distribution.getOrElse(distance) { 0.0 }
        }
        return probability.coerceIn(0.0, 1.0)
    }

    fun captureProbabilityWithinTurns(
        players: List<MatchPlayerState>,
        playerIndex: Int,
        cellKey: String,
        horizonTurns: Int,
    ): Double {
        var survivalProbability = 1.0
        players.forEachIndexed { opponentIndex, opponent ->
            if (opponentIndex == playerIndex || opponent.isEffectivelyAbandoned()) return@forEachIndexed
            val distances = opponent.tokens.mapIndexedNotNull { tokenIndex, progress ->
                forwardDistanceToCell(
                    color = opponent.color,
                    tokenIndex = tokenIndex,
                    fromProgress = progress,
                    targetKey = cellKey,
                    maxDistance = horizonTurns * 6,
                )
            }.toSet()
            survivalProbability *= 1.0 - probabilityOfAnyReach(distances, horizonTurns)
        }
        return (1.0 - survivalProbability).coerceIn(0.0, 1.0)
    }

    private fun probabilityOfAnyReach(distances: Set<Int>, horizonTurns: Int): Double {
        if (distances.isEmpty() || horizonTurns <= 0) return 0.0
        var successfulPaths = 0L
        var totalPaths = 0L

        fun visit(turn: Int, sum: Int, captured: Boolean) {
            if (turn == horizonTurns) {
                totalPaths += 1
                if (captured) successfulPaths += 1
                return
            }
            for (dice in 1..6) {
                val nextSum = sum + dice
                visit(turn + 1, nextSum, captured || nextSum in distances)
            }
        }

        visit(0, 0, false)
        return if (totalPaths == 0L) 0.0 else successfulPaths.toDouble() / totalPaths
    }

    fun countAttackers(
        players: List<MatchPlayerState>,
        playerIndex: Int,
        cellKey: String,
    ): Int {
        var attackers = 0
        players.forEachIndexed { opponentIndex, opponent ->
            if (opponentIndex == playerIndex || opponent.isEffectivelyAbandoned()) return@forEachIndexed
            val canReach = opponent.tokens.withIndex().any { (tokenIndex, progress) ->
                progress in 0..MAIN_PATH_LAST_PROGRESS &&
                    (1..6).any { dice ->
                        canMoveToken(progress, dice) &&
                            boardCellKey(opponent.color, progress + dice, tokenIndex) == cellKey
                    }
            }
            if (canReach) attackers += 1
        }
        return attackers
    }
}

/**
 * Shallow fair-dice expectimax with hard wall-clock budget.
 *
 * Bot already moved → avg opponent dice → opponent best-vs-bot →
 * (depth 3) avg bot dice → bot best → board value.
 */
internal object Expectimax {
    fun search(
        resultingPlayers: List<MatchPlayerState>,
        botIndex: Int,
        weights: BotRewardWeights,
        depth: Int,
        deadlineNs: Long,
    ): Double {
        if (System.nanoTime() > deadlineNs) {
            return BoardEvaluator.boardValue(resultingPlayers, botIndex, weights)
        }
        val nextOpponent = nextActiveIndex(resultingPlayers, botIndex + 1)
            ?: return BoardEvaluator.boardValue(resultingPlayers, botIndex, weights)
        if (nextOpponent == botIndex) {
            return BoardEvaluator.boardValue(resultingPlayers, botIndex, weights)
        }

        var expected = 0.0
        var samples = 0
        for (dice in 1..6) {
            if (System.nanoTime() > deadlineNs) break
            val legal = legalMoves(resultingPlayers[nextOpponent], dice)
            val value = if (legal.isEmpty()) {
                BoardEvaluator.boardValue(resultingPlayers, botIndex, weights)
            } else {
                // Opponent chooses the move that is worst for the bot.
                legal.minOf { move ->
                    val after = SuperiorBotEngine.simulateMove(resultingPlayers, nextOpponent, move, dice)
                    if (depth >= 3 && System.nanoTime() < deadlineNs) {
                        botReplyExpected(after, botIndex, weights, deadlineNs)
                    } else {
                        BoardEvaluator.boardValue(after, botIndex, weights)
                    }
                }
            }
            expected += value
            samples += 1
        }
        if (samples == 0) {
            return BoardEvaluator.boardValue(resultingPlayers, botIndex, weights)
        }
        // Blend look-ahead lightly so heuristic board terms still dominate.
        return expected / samples
    }

    private fun botReplyExpected(
        players: List<MatchPlayerState>,
        botIndex: Int,
        weights: BotRewardWeights,
        deadlineNs: Long,
    ): Double {
        var expected = 0.0
        var samples = 0
        for (dice in 1..6) {
            if (System.nanoTime() > deadlineNs) break
            val legal = legalMoves(players[botIndex], dice)
            val value = if (legal.isEmpty()) {
                BoardEvaluator.boardValue(players, botIndex, weights)
            } else {
                legal.maxOf { move ->
                    val after = SuperiorBotEngine.simulateMove(players, botIndex, move, dice)
                    BoardEvaluator.boardValue(after, botIndex, weights)
                }
            }
            expected += value
            samples += 1
        }
        return if (samples == 0) BoardEvaluator.boardValue(players, botIndex, weights) else expected / samples
    }

    private fun legalMoves(player: MatchPlayerState, dice: Int): List<CandidateMove> {
        return player.tokens.mapIndexedNotNull { tokenIndex, progress ->
            if (!canMoveToken(progress, dice)) {
                null
            } else {
                val to = if (progress == -1) 0 else progress + dice
                CandidateMove(tokenIndex, progress, to)
            }
        }
    }

    private fun nextActiveIndex(players: List<MatchPlayerState>, start: Int): Int? {
        repeat(players.size) { offset ->
            val index = (start + offset) % players.size
            if (!players[index].isEffectivelyAbandoned()) return index
        }
        return null
    }
}
