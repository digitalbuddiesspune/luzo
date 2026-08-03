package com.craft.ludo.gameplay.bot

import com.craft.ludo.gameplay.MAIN_PATH_LAST_PROGRESS
import com.craft.ludo.gameplay.MatchPlayerState
import com.craft.ludo.gameplay.boardCellKey
import com.craft.ludo.gameplay.isEffectivelyAbandoned
import com.craft.ludo.gameplay.ludoSafeCellKeys
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * A hunt the bot is committed to: it closes the gap to one human token over
 * several turns instead of rolling the exact capture face straight away.
 */
data class KillStalkPlan(
    val hunterPlayerIndex: Int,
    val hunterTokenIndex: Int,
    val targetPlayerIndex: Int,
    val targetTokenIndex: Int,
    val plannedRounds: Int,
    val roundsSpent: Int = 0,
)

/**
 * Dice face for a bot turn plus the hunt state that produced it.
 * [forcedTokenIndex] must be moved for the hunt to stay on track, so it is
 * carried on the match until the bot's move is applied.
 */
data class BotDiceDecision(
    val dice: Int,
    val stalkPlan: KillStalkPlan? = null,
    val forcedTokenIndex: Int? = null,
)

/** Share of engaged hunts that still capture on the spot, so kills stay unpredictable. */
internal const val STALK_INSTANT_KILL_PERCENT = 22

/** Farthest gap (in board steps) the bot will start closing on a human token. */
internal const val STALK_MAX_CHASE_STEPS = 12

internal const val STALK_MIN_PLANNED_ROUNDS = 2
internal const val STALK_MAX_PLANNED_ROUNDS = 3

/** Sixes grant an extra turn and can open yard tokens, so approach steps stay below six. */
private const val STALK_MAX_APPROACH_FACE = 5

private fun MatchPlayerState.isStalkableHunter(): Boolean = isBot && !isEffectivelyAbandoned()

private fun MatchPlayerState.isStalkableTarget(): Boolean = !isBot && !isEffectivelyAbandoned()

/** Board cell of a token worth hunting, or null when it cannot be captured there. */
internal fun stalkTargetCellKey(target: MatchPlayerState, targetTokenIndex: Int): String? {
    val progress = target.tokens.getOrNull(targetTokenIndex) ?: return null
    if (progress !in 0..MAIN_PATH_LAST_PROGRESS) {
        return null
    }
    val cellKey = boardCellKey(target.color, progress, targetTokenIndex)
    return cellKey.takeUnless { ludoSafeCellKeys.contains(it) }
}

/**
 * Board steps needed for one hunter token to land exactly on [targetCellKey],
 * or null when the cell is behind the token or out of [maxSteps] reach.
 */
internal fun stepsToReachCell(
    hunter: MatchPlayerState,
    hunterTokenIndex: Int,
    targetCellKey: String,
    maxSteps: Int = STALK_MAX_CHASE_STEPS,
): Int? {
    val progress = hunter.tokens.getOrNull(hunterTokenIndex) ?: return null
    if (progress !in 0..MAIN_PATH_LAST_PROGRESS) {
        return null
    }
    for (steps in 1..maxSteps) {
        val nextProgress = progress + steps
        if (nextProgress > MAIN_PATH_LAST_PROGRESS) {
            return null
        }
        if (boardCellKey(hunter.color, nextProgress, hunterTokenIndex) == targetCellKey) {
            return steps
        }
    }
    return null
}

internal data class StalkCandidate(
    val hunterTokenIndex: Int,
    val targetPlayerIndex: Int,
    val targetTokenIndex: Int,
    val steps: Int,
)

/** Every hunter/prey pairing within chasing range, including gaps larger than one roll. */
internal fun findStalkCandidates(
    players: List<MatchPlayerState>,
    playerIndex: Int,
    maxSteps: Int = STALK_MAX_CHASE_STEPS,
): List<StalkCandidate> {
    val hunter = players.getOrNull(playerIndex)?.takeIf { it.isStalkableHunter() } ?: return emptyList()
    val candidates = mutableListOf<StalkCandidate>()

    players.forEachIndexed { targetPlayerIndex, target ->
        if (targetPlayerIndex == playerIndex || !target.isStalkableTarget()) {
            return@forEachIndexed
        }
        for (targetTokenIndex in target.tokens.indices) {
            val targetCellKey = stalkTargetCellKey(target, targetTokenIndex) ?: continue
            for (hunterTokenIndex in hunter.tokens.indices) {
                val steps = stepsToReachCell(hunter, hunterTokenIndex, targetCellKey, maxSteps) ?: continue
                candidates.add(
                    StalkCandidate(
                        hunterTokenIndex = hunterTokenIndex,
                        targetPlayerIndex = targetPlayerIndex,
                        targetTokenIndex = targetTokenIndex,
                        steps = steps,
                    ),
                )
            }
        }
    }

    return candidates
}

/** True when any live opponent token could capture on [cellKey] with a single roll. */
internal fun isCellUnderOpponentFire(
    players: List<MatchPlayerState>,
    playerIndex: Int,
    cellKey: String,
): Boolean {
    if (ludoSafeCellKeys.contains(cellKey)) {
        return false
    }

    players.forEachIndexed { opponentIndex, opponent ->
        if (opponentIndex == playerIndex || opponent.isEffectivelyAbandoned()) {
            return@forEachIndexed
        }
        opponent.tokens.forEachIndexed { tokenIndex, progress ->
            if (progress !in 0..MAIN_PATH_LAST_PROGRESS) {
                return@forEachIndexed
            }
            for (dice in 1..6) {
                val nextProgress = progress + dice
                if (nextProgress > MAIN_PATH_LAST_PROGRESS) {
                    break
                }
                if (boardCellKey(opponent.color, nextProgress, tokenIndex) == cellKey) {
                    return true
                }
            }
        }
    }

    return false
}

/**
 * Face that closes part of the gap without landing on the prey yet, splitting
 * [steps] across [roundsLeft] so the hunt finishes roughly on schedule.
 * Returns null when the prey is already one step away and cannot be shadowed.
 */
private fun choosePartialFace(
    players: List<MatchPlayerState>,
    playerIndex: Int,
    hunterTokenIndex: Int,
    steps: Int,
    roundsLeft: Int,
    random: Random,
): Int? {
    val hunter = players.getOrNull(playerIndex) ?: return null
    val progress = hunter.tokens.getOrNull(hunterTokenIndex) ?: return null
    if (progress !in 0..MAIN_PATH_LAST_PROGRESS) {
        return null
    }

    val maxFace = min(STALK_MAX_APPROACH_FACE, steps - 1)
    if (maxFace < 1) {
        return null
    }

    // Leave at least one step for every round still to come.
    val reserved = max(1, roundsLeft) - 1
    val idealFace = ((steps + roundsLeft - 1) / max(1, roundsLeft)).coerceIn(1, maxFace)
    val window = (max(1, idealFace - 1)..min(maxFace, idealFace + 1))
        .filter { face -> face <= steps - reserved || face == 1 }
        .ifEmpty { listOf(idealFace) }

    val quietLandings = window.filter { face ->
        !isCellUnderOpponentFire(
            players = players,
            playerIndex = playerIndex,
            cellKey = boardCellKey(hunter.color, progress + face, hunterTokenIndex),
        )
    }
    val pool = quietLandings.ifEmpty { window }

    return pool[random.nextInt(pool.size)]
}

private fun finishingDecision(steps: Int, hunterTokenIndex: Int): BotDiceDecision {
    return BotDiceDecision(dice = steps, stalkPlan = null, forcedTokenIndex = hunterTokenIndex)
}

private fun canFinishThisTurn(steps: Int, allowSix: Boolean): Boolean {
    return steps in 1..6 && (allowSix || steps != 6)
}

/** Advance a hunt the bot already committed to, or null when the prey got away. */
private fun continueStalk(
    allowSix: Boolean,
    players: List<MatchPlayerState>,
    playerIndex: Int,
    plan: KillStalkPlan,
    random: Random,
): BotDiceDecision? {
    if (plan.hunterPlayerIndex != playerIndex) {
        return null
    }
    val hunter = players.getOrNull(playerIndex)?.takeIf { it.isStalkableHunter() } ?: return null
    val target = players.getOrNull(plan.targetPlayerIndex)?.takeIf { it.isStalkableTarget() } ?: return null
    val targetCellKey = stalkTargetCellKey(target, plan.targetTokenIndex) ?: return null
    val steps = stepsToReachCell(hunter, plan.hunterTokenIndex, targetCellKey) ?: return null

    val roundsLeft = max(1, plan.plannedRounds - plan.roundsSpent)
    if (canFinishThisTurn(steps, allowSix) && roundsLeft <= 1) {
        return finishingDecision(steps, plan.hunterTokenIndex)
    }

    val partialFace = choosePartialFace(
        players = players,
        playerIndex = playerIndex,
        hunterTokenIndex = plan.hunterTokenIndex,
        steps = steps,
        roundsLeft = roundsLeft,
        random = random,
    )
    if (partialFace == null) {
        return if (canFinishThisTurn(steps, allowSix)) {
            finishingDecision(steps, plan.hunterTokenIndex)
        } else {
            null
        }
    }

    return BotDiceDecision(
        dice = partialFace,
        stalkPlan = plan.copy(roundsSpent = plan.roundsSpent + 1),
        forcedTokenIndex = plan.hunterTokenIndex,
    )
}

/** Open a new hunt on the nearest human token, staging it over 2–3 rounds. */
private fun startStalk(
    allowSix: Boolean,
    players: List<MatchPlayerState>,
    playerIndex: Int,
    random: Random,
): BotDiceDecision? {
    val candidates = findStalkCandidates(players, playerIndex)
    if (candidates.isEmpty()) {
        return null
    }

    val nearestSteps = candidates.minOf { candidate -> candidate.steps }
    val nearest = candidates.filter { candidate -> candidate.steps == nearestSteps }
    val chosen = nearest[random.nextInt(nearest.size)]
    val canFinishNow = canFinishThisTurn(chosen.steps, allowSix)

    if (canFinishNow && random.nextInt(100) < STALK_INSTANT_KILL_PERCENT) {
        return finishingDecision(chosen.steps, chosen.hunterTokenIndex)
    }

    val plannedRounds = random.nextInt(STALK_MIN_PLANNED_ROUNDS, STALK_MAX_PLANNED_ROUNDS + 1)
    val partialFace = choosePartialFace(
        players = players,
        playerIndex = playerIndex,
        hunterTokenIndex = chosen.hunterTokenIndex,
        steps = chosen.steps,
        roundsLeft = plannedRounds,
        random = random,
    )
    if (partialFace == null) {
        return if (canFinishNow) finishingDecision(chosen.steps, chosen.hunterTokenIndex) else null
    }

    return BotDiceDecision(
        dice = partialFace,
        stalkPlan = KillStalkPlan(
            hunterPlayerIndex = playerIndex,
            hunterTokenIndex = chosen.hunterTokenIndex,
            targetPlayerIndex = chosen.targetPlayerIndex,
            targetTokenIndex = chosen.targetTokenIndex,
            plannedRounds = plannedRounds,
            roundsSpent = 1,
        ),
        forcedTokenIndex = chosen.hunterTokenIndex,
    )
}

/**
 * Dice face for a bot that hunts human tokens across turns rather than snapping
 * to the capture face. An in-flight hunt always continues; [killFavorPercent]
 * only gates whether a new hunt starts. Null means roll normally.
 */
internal fun resolveStalkDice(
    allowSix: Boolean,
    players: List<MatchPlayerState>,
    playerIndex: Int,
    killFavorPercent: Int,
    existingPlan: KillStalkPlan?,
    random: Random,
): BotDiceDecision? {
    val hunter = players.getOrNull(playerIndex) ?: return null
    if (!hunter.isStalkableHunter()) {
        return null
    }

    if (existingPlan != null) {
        val continued = continueStalk(allowSix, players, playerIndex, existingPlan, random)
        if (continued != null) {
            return continued
        }
    }

    if (random.nextInt(100) >= killFavorPercent) {
        return null
    }

    return startStalk(allowSix, players, playerIndex, random)
}

/** Hunt this bot is currently committed to, if any. */
fun stalkPlanFor(plans: List<KillStalkPlan>, playerIndex: Int): KillStalkPlan? {
    return plans.firstOrNull { plan -> plan.hunterPlayerIndex == playerIndex }
}

/** Replace only this bot's hunt, leaving other bots' hunts untouched. */
fun upsertStalkPlan(
    plans: List<KillStalkPlan>,
    playerIndex: Int,
    plan: KillStalkPlan?,
): List<KillStalkPlan> {
    val others = plans.filter { existing -> existing.hunterPlayerIndex != playerIndex }
    return if (plan == null) others else others + plan
}
