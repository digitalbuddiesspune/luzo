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
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Superior Ludo bot decision engine.
 *
 * Strength comes only from evaluating legal moves — dice are never manipulated.
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
        if (movableTokenIndexes.isEmpty()) {
            return 0
        }
        if (movableTokenIndexes.size == 1) {
            return movableTokenIndexes.first()
        }

        val activeDifficulty = difficultyOverride ?: difficulty
        val activeWeights = weightsOverride ?: weights
        val startedAt = System.nanoTime()

        val evaluations = when (activeDifficulty) {
            BotDifficulty.EASY -> evaluateEasy(players, playerIndex, movableTokenIndexes, diceValue, activeWeights, random)
            BotDifficulty.MEDIUM -> evaluateMedium(players, playerIndex, movableTokenIndexes, diceValue, activeWeights)
            BotDifficulty.HARD,
            BotDifficulty.EXPERT,
            BotDifficulty.SUPER,
            -> evaluateStrategic(
                players = players,
                playerIndex = playerIndex,
                movableTokenIndexes = movableTokenIndexes,
                diceValue = diceValue,
                weights = activeWeights,
                useExpectimax = activeDifficulty != BotDifficulty.HARD,
                random = random,
            )
        }

        val best = selectPriorityMove(
            players = players,
            playerIndex = playerIndex,
            diceValue = diceValue,
            evaluations = evaluations,
            random = random,
        )
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        if (log.isDebugEnabled) {
            val player = players[playerIndex]
            log.debug(
                "BOT_DECISION player={} dice={} difficulty={} selected=T{} score={} reason=\"{}\" decisionTimeMs={}",
                player.displayName,
                diceValue,
                activeDifficulty,
                best.move.tokenIndex + 1,
                best.finalScore.roundToInt(),
                best.reason,
                elapsedMs,
            )
            evaluations.sortedByDescending { it.finalScore }.forEach { evaluation ->
                log.debug(
                    "candidate=T{} progress={} safety={} attack={} risk={} future={} finalScore={} reason=\"{}\"",
                    evaluation.move.tokenIndex + 1,
                    evaluation.progressReward.roundToInt(),
                    evaluation.safetyReward.roundToInt(),
                    evaluation.attackReward.roundToInt(),
                    evaluation.riskPenalty.roundToInt(),
                    evaluation.futureValue.roundToInt(),
                    evaluation.finalScore.roundToInt(),
                    evaluation.reason,
                )
            }
        }

        return best.move.tokenIndex
    }

    /**
     * Hard move priorities:
     * 1. Complete the match when a legal move wins immediately.
     * 2. Capture an opponent token.
     * 3. Move a token from the main path into the protected home lane.
     * 4. Finish a token at home.
     * 5. Fall back to the highest scored strategic move.
     *
     * These priorities only rank legal moves; dice outcomes remain random.
     */
    private fun selectPriorityMove(
        players: List<MatchPlayerState>,
        playerIndex: Int,
        diceValue: Int,
        evaluations: List<MoveEvaluation>,
        random: Random,
    ): MoveEvaluation {
        fun isWinning(evaluation: MoveEvaluation): Boolean {
            val resulting = simulateMove(players, playerIndex, evaluation.move, diceValue)
            return tokensAllFinished(resulting[playerIndex])
        }

        fun isCapture(evaluation: MoveEvaluation): Boolean {
            return captureTargets(players, playerIndex, evaluation.move).isNotEmpty()
        }

        fun entersHomeLane(evaluation: MoveEvaluation): Boolean {
            val move = evaluation.move
            return move.fromProgress <= MAIN_PATH_LAST_PROGRESS &&
                move.toProgress in HOME_LANE_START_PROGRESS..HOME_LANE_LAST_PROGRESS
        }

        fun finishesToken(evaluation: MoveEvaluation): Boolean {
            return evaluation.move.toProgress == FINISHED_PROGRESS
        }

        val winningMoves = evaluations.filter(::isWinning)
        if (winningMoves.isNotEmpty()) {
            return tieBreak(winningMoves, random)
        }

        val capturingMoves = evaluations.filter(::isCapture)
        if (capturingMoves.isNotEmpty()) {
            return tieBreak(capturingMoves, random)
        }

        val homeLaneMoves = evaluations.filter(::entersHomeLane)
        if (homeLaneMoves.isNotEmpty()) {
            return tieBreak(homeLaneMoves, random)
        }

        val tokenFinishingMoves = evaluations.filter(::finishesToken)
        if (tokenFinishingMoves.isNotEmpty()) {
            return tieBreak(tokenFinishingMoves, random)
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
            val progress = progressReward(move.fromProgress, move.toProgress, weights)
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
            )
        }
    }

    private fun evaluateMedium(
        players: List<MatchPlayerState>,
        playerIndex: Int,
        movableTokenIndexes: List<Int>,
        diceValue: Int,
        weights: BotRewardWeights,
    ): List<MoveEvaluation> {
        return movableTokenIndexes.map { tokenIndex ->
            val move = candidateMove(players[playerIndex], tokenIndex, diceValue)
            val resultingPlayers = simulateMove(players, playerIndex, move, diceValue)
            val immediate = immediateReward(players, resultingPlayers, playerIndex, move, weights)
            val progress = progressReward(move.fromProgress, move.toProgress, weights)
            val safety = basicSafety(players, playerIndex, move, weights)
            val score = immediate + progress + safety
            MoveEvaluation(
                move = move,
                immediateReward = immediate,
                progressReward = progress,
                safetyReward = safety,
                attackReward = 0.0,
                strategicReward = 0.0,
                riskPenalty = 0.0,
                futureValue = 0.0,
                finalScore = score,
                reason = summarizeReason(immediate, progress, safety, 0.0, 0.0, 0.0, 0.0),
            )
        }
    }

    private fun evaluateStrategic(
        players: List<MatchPlayerState>,
        playerIndex: Int,
        movableTokenIndexes: List<Int>,
        diceValue: Int,
        weights: BotRewardWeights,
        useExpectimax: Boolean,
        random: Random,
    ): List<MoveEvaluation> {
        val deadlineNs = System.nanoTime() + weights.maxDecisionMillis * 1_000_000
        val activeHumanOrBotCount = players.count { !it.isEffectivelyAbandoned() }
        val twoPlayer = activeHumanOrBotCount == 2
        val threatByOpponent = players.mapIndexed { index, _ ->
            if (index == playerIndex || players[index].isEffectivelyAbandoned()) {
                0.0
            } else {
                opponentThreat(players, playerIndex, index)
            }
        }

        val guaranteedCaptureExists = movableTokenIndexes.any { tokenIndex ->
            val move = candidateMove(players[playerIndex], tokenIndex, diceValue)
            captureTargets(players, playerIndex, move).isNotEmpty()
        }

        return movableTokenIndexes.map { tokenIndex ->
            val move = candidateMove(players[playerIndex], tokenIndex, diceValue)
            val resultingPlayers = simulateMove(players, playerIndex, move, diceValue)
            val captures = captureTargets(players, playerIndex, move)

            val immediate = immediateReward(players, resultingPlayers, playerIndex, move, weights)
            val progress = progressReward(move.fromProgress, move.toProgress, weights)
            val safety = safetyReward(players, playerIndex, move, weights)
            val attack = attackReward(
                players = players,
                playerIndex = playerIndex,
                move = move,
                captures = captures,
                resultingPlayers = resultingPlayers,
                threatByOpponent = threatByOpponent,
                twoPlayer = twoPlayer,
                weights = weights,
            )
            val strategic = strategicReward(
                players = players,
                playerIndex = playerIndex,
                move = move,
                resultingPlayers = resultingPlayers,
                weights = weights,
            )
            val risk = captureRiskPenalty(resultingPlayers, playerIndex, move.tokenIndex, weights)
            var future = 0.0
            if (
                useExpectimax &&
                System.nanoTime() < deadlineNs &&
                weights.expectimaxDepth >= 2
            ) {
                future = expectimaxOpponentPly(
                    resultingPlayers = resultingPlayers,
                    botIndex = playerIndex,
                    weights = weights,
                    deadlineNs = deadlineNs,
                )
            }

            var finalScore =
                immediate + progress + safety + attack + strategic + future + risk

            if (
                guaranteedCaptureExists &&
                captures.isEmpty() &&
                !tokensAllFinished(resultingPlayers[playerIndex])
            ) {
                // Strongly punish skipping a kill when one is available (unless this move wins).
                finalScore += weights.ignoreGuaranteedCapture
            }

            MoveEvaluation(
                move = move,
                immediateReward = immediate,
                progressReward = progress,
                safetyReward = safety,
                attackReward = attack,
                strategicReward = strategic,
                riskPenalty = risk,
                futureValue = future,
                finalScore = finalScore,
                reason = summarizeReason(immediate, progress, safety, attack, strategic, risk, future),
            )
        }
    }

    private fun candidateMove(player: MatchPlayerState, tokenIndex: Int, diceValue: Int): CandidateMove {
        val from = player.tokens[tokenIndex]
        val to = if (from == -1) 0 else from + diceValue
        return CandidateMove(tokenIndex = tokenIndex, fromProgress = from, toProgress = to)
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

    private fun immediateReward(
        before: List<MatchPlayerState>,
        after: List<MatchPlayerState>,
        playerIndex: Int,
        move: CandidateMove,
        weights: BotRewardWeights,
    ): Double {
        var reward = 0.0
        if (tokensAllFinished(after[playerIndex])) {
            return weights.winGame
        }
        if (move.toProgress == FINISHED_PROGRESS) {
            reward += weights.tokenHome
        }
        if (move.fromProgress == -1 && move.toProgress == 0) {
            val activeBefore = before[playerIndex].tokens.count { it in 0 until FINISHED_PROGRESS }
            reward += when (activeBefore) {
                0 -> weights.leaveBase * 2.2
                1 -> weights.leaveBase * 1.4
                2 -> weights.leaveBase * 0.7
                else -> weights.leaveBase * -0.4
            }
        }
        if (move.toProgress in HOME_LANE_START_PROGRESS..HOME_LANE_LAST_PROGRESS) {
            reward += weights.enterHomePath
        }
        return reward
    }

    private fun progressReward(from: Int, to: Int, weights: BotRewardWeights): Double {
        if (to < 0 || to > FINISHED_PROGRESS) {
            return 0.0
        }
        val steps = if (from < 0) 1 else max(0, to - from)
        val ratio = to.toDouble() / FINISHED_PROGRESS.toDouble()
        val multiplier = when {
            ratio < 0.25 -> 1.0
            ratio < 0.50 -> 1.2
            ratio < 0.75 -> 1.5
            ratio < 0.90 -> 2.0
            else -> 3.0
        }
        return steps * weights.progressPerStep * multiplier
    }

    private fun basicSafety(
        players: List<MatchPlayerState>,
        playerIndex: Int,
        move: CandidateMove,
        weights: BotRewardWeights,
    ): Double {
        if (move.toProgress in HOME_LANE_START_PROGRESS..FINISHED_PROGRESS) {
            return weights.enterHomePath * 0.5
        }
        if (move.toProgress !in 0..MAIN_PATH_LAST_PROGRESS) {
            return 0.0
        }
        val landingKey = boardCellKey(players[playerIndex].color, move.toProgress, move.tokenIndex)
        if (ludoSafeCellKeys.contains(landingKey)) {
            return weights.landSafe
        }
        val attackers = countAttackers(players, playerIndex, landingKey)
        return when {
            attackers >= 2 -> weights.multiOpponentDanger * 0.5
            attackers == 1 -> weights.exposeToCapture * 0.5
            else -> 0.0
        }
    }

    private fun safetyReward(
        players: List<MatchPlayerState>,
        playerIndex: Int,
        move: CandidateMove,
        weights: BotRewardWeights,
    ): Double {
        var reward = 0.0
        val player = players[playerIndex]
        val wasThreatened =
            move.fromProgress in 0..MAIN_PATH_LAST_PROGRESS &&
                !ludoSafeCellKeys.contains(boardCellKey(player.color, move.fromProgress, move.tokenIndex)) &&
                countAttackers(players, playerIndex, boardCellKey(player.color, move.fromProgress, move.tokenIndex)) > 0

        if (move.toProgress in HOME_LANE_START_PROGRESS..HOME_LANE_LAST_PROGRESS) {
            reward += weights.enterHomePath
        }
        if (move.toProgress in 0..MAIN_PATH_LAST_PROGRESS) {
            val landingKey = boardCellKey(player.color, move.toProgress, move.tokenIndex)
            val attackers = if (ludoSafeCellKeys.contains(landingKey)) {
                0
            } else {
                countAttackers(players, playerIndex, landingKey)
            }

            if (ludoSafeCellKeys.contains(landingKey)) {
                reward += weights.landSafe
            } else {
                if (attackers >= 2) {
                    reward += weights.multiOpponentDanger
                } else if (attackers == 1) {
                    reward += weights.exposeToCapture
                }
                if (
                    move.fromProgress in 0..MAIN_PATH_LAST_PROGRESS &&
                    ludoSafeCellKeys.contains(boardCellKey(player.color, move.fromProgress, move.tokenIndex)) &&
                    attackers > 0
                ) {
                    reward += weights.leaveSafetyIntoDanger
                }
            }

            if (wasThreatened && attackers == 0) {
                reward += weights.escapeThreat + weights.saveThreatened
            }
        } else if (wasThreatened && move.toProgress >= HOME_LANE_START_PROGRESS) {
            reward += weights.escapeThreat + weights.saveThreatened
        } else if (wasThreatened && move.toProgress > move.fromProgress) {
            // Moved forward but still evaluate destination risk elsewhere.
            reward += weights.saveThreatened * 0.5
        }
        return reward
    }

    private fun attackReward(
        players: List<MatchPlayerState>,
        playerIndex: Int,
        move: CandidateMove,
        captures: List<Pair<Int, Int>>,
        resultingPlayers: List<MatchPlayerState>,
        threatByOpponent: List<Double>,
        twoPlayer: Boolean,
        weights: BotRewardWeights,
    ): Double {
        var reward = 0.0
        captures.forEach { (opponentIndex, opponentProgress) ->
            val progressBonus = opponentProgress * 4.0
            val nearHomeBonus = if (opponentProgress >= 40) 180.0 else if (opponentProgress >= 25) 80.0 else 0.0
            val threatBonus = threatByOpponent.getOrElse(opponentIndex) { 0.0 } * 40.0
            var captureValue = weights.captureBase + progressBonus + nearHomeBonus + threatBonus
            if (twoPlayer) {
                captureValue *= weights.twoPlayerAttackMultiplier
            }
            val landingKey = boardCellKey(
                resultingPlayers[playerIndex].color,
                move.toProgress,
                move.tokenIndex,
            )
            val postDanger = countAttackers(resultingPlayers, playerIndex, landingKey)
            if (postDanger > 0 && move.toProgress in 0..MAIN_PATH_LAST_PROGRESS && !ludoSafeCellKeys.contains(landingKey)) {
                captureValue -= 220.0 * postDanger
            }
            reward += captureValue
        }

        // Creating an immediate capture threat for next turn.
        if (move.toProgress in 0..MAIN_PATH_LAST_PROGRESS) {
            for (dice in 1..6) {
                if (!canMoveToken(move.toProgress, dice)) continue
                val reach = move.toProgress + dice
                if (reach !in 0..MAIN_PATH_LAST_PROGRESS) continue
                val reachKey = boardCellKey(players[playerIndex].color, reach, move.tokenIndex)
                if (ludoSafeCellKeys.contains(reachKey)) continue
                players.forEachIndexed { opponentIndex, opponent ->
                    if (opponentIndex == playerIndex || opponent.isEffectivelyAbandoned()) return@forEachIndexed
                    opponent.tokens.forEachIndexed { tokenIndex, progress ->
                        if (
                            progress in 0..MAIN_PATH_LAST_PROGRESS &&
                            boardCellKey(opponent.color, progress, tokenIndex) == reachKey
                        ) {
                            reward += weights.createCaptureThreat / 6.0
                        }
                    }
                }
            }
        }
        return reward
    }

    private fun strategicReward(
        players: List<MatchPlayerState>,
        playerIndex: Int,
        move: CandidateMove,
        resultingPlayers: List<MatchPlayerState>,
        weights: BotRewardWeights,
    ): Double {
        var reward = 0.0
        val beforeStacks = ownStacks(players[playerIndex])
        val afterStacks = ownStacks(resultingPlayers[playerIndex])
        if (afterStacks > beforeStacks) {
            reward += weights.createBlockade
        } else if (afterStacks < beforeStacks && move.toProgress != FINISHED_PROGRESS) {
            reward += weights.breakOwnBlockade * 0.5
        } else if (afterStacks > 0) {
            reward += weights.maintainBlockade * 0.25
        }
        return reward
    }

    private fun captureRiskPenalty(
        players: List<MatchPlayerState>,
        playerIndex: Int,
        tokenIndex: Int,
        weights: BotRewardWeights,
    ): Double {
        val progress = players[playerIndex].tokens[tokenIndex]
        if (progress !in 0..MAIN_PATH_LAST_PROGRESS) {
            return 0.0
        }
        val key = boardCellKey(players[playerIndex].color, progress, tokenIndex)
        if (ludoSafeCellKeys.contains(key)) {
            return 0.0
        }
        val attackers = countAttackers(players, playerIndex, key)
        return when {
            attackers >= 2 -> weights.multiOpponentDanger * 0.35
            attackers == 1 -> weights.exposeToCapture * 0.25
            else -> 0.0
        }
    }

    private fun expectimaxOpponentPly(
        resultingPlayers: List<MatchPlayerState>,
        botIndex: Int,
        weights: BotRewardWeights,
        deadlineNs: Long,
    ): Double {
        val nextOpponent = nextActiveIndex(resultingPlayers, botIndex + 1) ?: return boardValue(resultingPlayers, botIndex)
        if (nextOpponent == botIndex) {
            return boardValue(resultingPlayers, botIndex)
        }

        var expected = 0.0
        for (dice in 1..6) {
            if (System.nanoTime() > deadlineNs) {
                return expected / max(1, dice - 1)
            }
            val legal = resultingPlayers[nextOpponent].tokens.mapIndexedNotNull { tokenIndex, progress ->
                tokenIndex.takeIf { canMoveToken(progress, dice) }
            }
            if (legal.isEmpty()) {
                expected += boardValue(resultingPlayers, botIndex)
                continue
            }
            val worstForBot = legal.minOf { tokenIndex ->
                val move = candidateMove(resultingPlayers[nextOpponent], tokenIndex, dice)
                val after = simulateMove(resultingPlayers, nextOpponent, move, dice)
                boardValue(after, botIndex)
            }
            expected += worstForBot
        }
        return expected / 6.0 * 0.15
    }

    private fun boardValue(players: List<MatchPlayerState>, botIndex: Int): Double {
        val bot = players[botIndex]
        if (tokensAllFinished(bot)) {
            return 10_000.0
        }
        var value = bot.tokens.sumOf { progress ->
            when {
                progress == FINISHED_PROGRESS -> 180.0
                progress >= HOME_LANE_START_PROGRESS -> 90.0 + (progress - HOME_LANE_START_PROGRESS) * 12.0
                progress >= 0 -> progress * 1.6
                else -> -8.0
            }
        }
        players.forEachIndexed { index, opponent ->
            if (index == botIndex || opponent.isEffectivelyAbandoned()) return@forEachIndexed
            value -= opponent.tokens.sumOf { progress ->
                when {
                    progress == FINISHED_PROGRESS -> 160.0
                    progress >= HOME_LANE_START_PROGRESS -> 70.0 + (progress - HOME_LANE_START_PROGRESS) * 10.0
                    progress >= 0 -> progress * 1.1
                    else -> 0.0
                }
            } * 0.35
        }
        return value
    }

    private fun opponentThreat(
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

    private fun captureTargets(
        players: List<MatchPlayerState>,
        playerIndex: Int,
        move: CandidateMove,
    ): List<Pair<Int, Int>> {
        if (move.toProgress !in 0..MAIN_PATH_LAST_PROGRESS) {
            return emptyList()
        }
        val landingKey = boardCellKey(players[playerIndex].color, move.toProgress, move.tokenIndex)
        if (ludoSafeCellKeys.contains(landingKey)) {
            return emptyList()
        }
        val targets = mutableListOf<Pair<Int, Int>>()
        players.forEachIndexed { opponentIndex, opponent ->
            if (opponentIndex == playerIndex || opponent.isEffectivelyAbandoned()) {
                return@forEachIndexed
            }
            opponent.tokens.forEachIndexed { tokenIndex, progress ->
                if (
                    progress in 0..MAIN_PATH_LAST_PROGRESS &&
                    boardCellKey(opponent.color, progress, tokenIndex) == landingKey
                ) {
                    targets += opponentIndex to progress
                }
            }
        }
        return targets
    }

    private fun countAttackers(
        players: List<MatchPlayerState>,
        playerIndex: Int,
        cellKey: String,
    ): Int {
        var attackers = 0
        players.forEachIndexed { opponentIndex, opponent ->
            if (opponentIndex == playerIndex || opponent.isEffectivelyAbandoned()) {
                return@forEachIndexed
            }
            val canReach = opponent.tokens.withIndex().any { (tokenIndex, progress) ->
                if (progress !in 0..MAIN_PATH_LAST_PROGRESS) {
                    false
                } else {
                    (1..6).any { dice ->
                        canMoveToken(progress, dice) &&
                            boardCellKey(opponent.color, progress + dice, tokenIndex) == cellKey
                    }
                }
            }
            if (canReach) {
                attackers += 1
            }
        }
        return attackers
    }

    private fun ownStacks(player: MatchPlayerState): Int {
        val counts = mutableMapOf<String, Int>()
        player.tokens.forEachIndexed { tokenIndex, progress ->
            if (progress in 0..MAIN_PATH_LAST_PROGRESS) {
                val key = boardCellKey(player.color, progress, tokenIndex)
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        return counts.values.count { it >= 2 }
    }

    private fun tokensAllFinished(player: MatchPlayerState): Boolean =
        player.tokens.isNotEmpty() && player.tokens.all { it == FINISHED_PROGRESS }

    private fun nextActiveIndex(players: List<MatchPlayerState>, start: Int): Int? {
        repeat(players.size) { offset ->
            val index = (start + offset) % players.size
            if (!players[index].isEffectivelyAbandoned()) {
                return index
            }
        }
        return null
    }

    private fun summarizeReason(
        immediate: Double,
        progress: Double,
        safety: Double,
        attack: Double,
        strategic: Double,
        risk: Double,
        future: Double,
    ): String {
        val parts = linkedMapOf(
            "win/home" to immediate,
            "progress" to progress,
            "safety" to safety,
            "attack" to attack,
            "strategy" to strategic,
            "future" to future,
            "risk" to risk,
        )
        val top = parts.maxByOrNull { it.value } ?: return "Balanced move"
        return when {
            top.value >= 900 -> "Winning / finishing priority"
            top.key == "attack" && top.value >= 500 -> "High-value capture with acceptable future risk"
            top.key == "safety" && top.value >= 200 -> "Escape or safe positioning"
            top.key == "progress" -> "Useful forward progress"
            top.key == "future" -> "Better expected future state"
            else -> "Best overall EV (${top.key})"
        }
    }

    private fun tieBreak(evaluations: List<MoveEvaluation>, random: Random): MoveEvaluation {
        val bestScore = evaluations.maxOf { it.finalScore }
        val tied = evaluations.filter { kotlin.math.abs(it.finalScore - bestScore) < 1e-6 }
        if (tied.size == 1) {
            return tied.first()
        }

        fun rank(evaluation: MoveEvaluation): Int {
            val move = evaluation.move
            return when {
                evaluation.immediateReward >= 9_000 -> 0
                move.toProgress == FINISHED_PROGRESS -> 1
                move.toProgress in HOME_LANE_START_PROGRESS..HOME_LANE_LAST_PROGRESS -> 2
                evaluation.safetyReward >= 400 -> 3
                evaluation.attackReward >= 700 && evaluation.riskPenalty > -200 -> 4
                evaluation.safetyReward >= 150 -> 5
                evaluation.strategicReward >= 100 -> 6
                evaluation.progressReward > 0 -> 7
                else -> 8
            }
        }

        val bestRank = tied.minOf(::rank)
        val ranked = tied.filter { rank(it) == bestRank }
        return ranked[random.nextInt(ranked.size)]
    }
}
