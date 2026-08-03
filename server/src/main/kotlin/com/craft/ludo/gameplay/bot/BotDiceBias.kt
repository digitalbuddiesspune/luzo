package com.craft.ludo.gameplay.bot

import com.craft.ludo.gameplay.FINISHED_PROGRESS
import com.craft.ludo.gameplay.MAIN_PATH_LAST_PROGRESS
import com.craft.ludo.gameplay.MatchPlayerState
import com.craft.ludo.gameplay.boardCellKey
import com.craft.ludo.gameplay.canMoveToken
import com.craft.ludo.gameplay.isEffectivelyAbandoned
import com.craft.ludo.gameplay.ludoSafeCellKeys
import kotlin.random.Random

/**
 * Dice bias settings. Tuned in code only, so online matches always behave like
 * the offline board. Keep the defaults in sync with the mirrored constants in
 * `client/app/components/ludo-shell.js`.
 */
data class BotDiceSettings(
    val botKillFavor2Player: Int = DEFAULT_BOT_KILL_FAVOR_PERCENT,
    val userKillFavor2Player: Int = DEFAULT_USER_KILL_FAVOR_PERCENT,
    val botKillFavorMultiPlayer: Int = DEFAULT_BOT_KILL_FAVOR_PERCENT,
    val userKillFavorMultiPlayer: Int = DEFAULT_USER_KILL_FAVOR_PERCENT,
    val botSixBoostPercent: Int = DEFAULT_SIX_BOOST_PERCENT,
) {
    fun botKillFavorFor(seatCount: Int): Int {
        return if (seatCount == 2) botKillFavor2Player else botKillFavorMultiPlayer
    }

    fun userKillFavorFor(seatCount: Int): Int {
        return if (seatCount == 2) userKillFavor2Player else userKillFavorMultiPlayer
    }

    companion object {
        const val DEFAULT_BOT_KILL_FAVOR_PERCENT = 30
        const val DEFAULT_USER_KILL_FAVOR_PERCENT = 30
        const val DEFAULT_SIX_BOOST_PERCENT = 15

        val DEFAULT = BotDiceSettings()
    }
}

/** Per-match dice context for six balancing and first-six window rules. */
data class DiceRollContext(
    val rollerMatchDiceRollCount: Int = 0,
    val rollerMatchSixCount: Int = 0,
    val botMatchSixRolls: Int = 0,
    val playerMatchSixRolls: Int = 0,
) {
    companion object {
        val DEFAULT = DiceRollContext()
    }
}

internal const val TARGET_PLAYER_TO_BOT_SIX_RATIO = 7.0 / 11.0
internal const val FIRST_SIX_MIN_ROLL_NUMBER = 2
internal const val FIRST_SIX_MAX_ROLL_NUMBER = 6
internal const val MIN_TOTAL_SIXES_BEFORE_BALANCE = 3
internal const val MAX_SIX_PROBABILITY = 0.35
/**
 * Bot→bot kill favor (~2–3 of 10 chances).
 * Keeps occasional bot-vs-bot kills feeling natural without farming.
 */
internal const val BOT_VS_BOT_KILL_FAVOR_PERCENT = 25
/**
 * Favor for the exact face that walks a bot token onto its home square.
 * Matches are won by bringing four tokens home rather than by capturing, and an
 * "exact number" reads as luck far more than a suspicious run of kills does.
 */
internal const val BOT_HOME_FINISH_FAVOR_PERCENT = 60
/** Hardcoded move-AI scale when capturing another bot (vs real player). */
internal const val BOT_VS_BOT_CAPTURE_SCORE_MULTIPLIER = 0.28
/**
 * Soft "pack hunt" when 2+ bots share a table with at least one human.
 * Biases move scoring toward the human without hard-forcing scammy plays.
 */
internal const val PACK_HUNT_HUMAN_CAPTURE_MULT = 1.32
internal const val PACK_HUNT_HUMAN_HUNT_MULT = 1.48
internal const val PACK_HUNT_BOT_HUNT_MULT = 0.32
internal const val PACK_HUNT_ALLY_DANGER_MULT = 0.40
internal const val PACK_HUNT_HUMAN_THREAT_MULT = 1.35

fun isPackHuntHumanTable(players: List<MatchPlayerState>): Boolean {
    val active = players.filter { !it.isEffectivelyAbandoned() }
    val bots = active.count { it.isBot }
    val humans = active.count { !it.isBot }
    return bots >= 2 && humans >= 1
}

fun aggregateBotMatchSixRolls(players: List<MatchPlayerState>): Int {
    return players
        .filter { player -> player.isBot && !player.isEffectivelyAbandoned() }
        .sumOf { player -> player.matchSixCount }
}

fun aggregatePlayerMatchSixRolls(players: List<MatchPlayerState>): Int {
    return players
        .filter { player -> !player.isBot && !player.isEffectivelyAbandoned() }
        .sumOf { player -> player.matchSixCount }
}

fun buildDiceRollContext(players: List<MatchPlayerState>, playerIndex: Int): DiceRollContext {
    val roller = players.getOrNull(playerIndex) ?: return DiceRollContext.DEFAULT
    return DiceRollContext(
        rollerMatchDiceRollCount = roller.matchDiceRollCount,
        rollerMatchSixCount = roller.matchSixCount,
        botMatchSixRolls = aggregateBotMatchSixRolls(players),
        playerMatchSixRolls = aggregatePlayerMatchSixRolls(players),
    )
}

fun incrementPlayerRollStats(
    players: List<MatchPlayerState>,
    playerIndex: Int,
    dice: Int,
): List<MatchPlayerState> {
    return players.mapIndexed { index, player ->
        if (index != playerIndex) {
            player
        } else {
            player.copy(
                matchDiceRollCount = player.matchDiceRollCount + 1,
                matchSixCount = player.matchSixCount + if (dice == 6) 1 else 0,
            )
        }
    }
}

/** First match six only on rolls 2–6 (roll 1 blocked; roll 7+ allowed as fallback). */
fun allowsFirstSixOnThisRoll(rollerMatchSixCount: Int, nextRollNumber: Int): Boolean {
    if (rollerMatchSixCount > 0) {
        return true
    }
    if (nextRollNumber < FIRST_SIX_MIN_ROLL_NUMBER) {
        return false
    }
    return true
}

fun computeSixProbabilityNudge(
    isBot: Boolean,
    botMatchSixRolls: Int,
    playerMatchSixRolls: Int,
    random: Random,
): Double {
    val totalSixes = botMatchSixRolls + playerMatchSixRolls
    if (totalSixes < MIN_TOTAL_SIXES_BEFORE_BALANCE) {
        return 0.0
    }

    val expectedPlayerSixes = botMatchSixRolls * TARGET_PLAYER_TO_BOT_SIX_RATIO
    val playerGap = expectedPlayerSixes - playerMatchSixRolls
    val expectedBotSixes = if (TARGET_PLAYER_TO_BOT_SIX_RATIO <= 0.0) {
        botMatchSixRolls.toDouble()
    } else {
        playerMatchSixRolls / TARGET_PLAYER_TO_BOT_SIX_RATIO
    }
    val botGap = expectedBotSixes - botMatchSixRolls
    val jitter = (random.nextDouble() - 0.5) * 0.02

    return if (isBot) {
        when {
            botGap > 0.75 -> (botGap * 0.03).coerceIn(0.0, 0.10) + jitter
            botGap < -1.0 -> (botGap * 0.015).coerceIn(-0.05, 0.0) + jitter
            else -> jitter * 0.4
        }
    } else {
        when {
            playerGap > 0.75 -> (playerGap * 0.022).coerceIn(0.0, 0.08) + jitter
            playerGap < -1.0 -> (playerGap * 0.015).coerceIn(-0.05, 0.0) + jitter
            else -> jitter * 0.4
        }
    }
}

/** Gentle boost while still hunting for the first six inside rolls 2–6. */
fun firstSixWindowNudge(rollerMatchSixCount: Int, nextRollNumber: Int, random: Random): Double {
    if (rollerMatchSixCount > 0) {
        return 0.0
    }
    if (nextRollNumber !in FIRST_SIX_MIN_ROLL_NUMBER..FIRST_SIX_MAX_ROLL_NUMBER) {
        return 0.0
    }
    return 0.03 + random.nextDouble() * 0.02
}

/**
 * Dice values (1–6) that would capture an opponent token for this player.
 * When the roller is a bot, only kills on real human opponents count for kill favor.
 */
fun findKillDiceValues(
    players: List<MatchPlayerState>,
    playerIndex: Int,
): List<Int> {
    return findCaptureDiceValues(players, playerIndex, humansOnly = true)
}

/**
 * Dice faces that would kill another bot and would not also kill a real player.
 */
fun findBotOnlyKillDiceValues(
    players: List<MatchPlayerState>,
    playerIndex: Int,
): List<Int> {
    val roller = players.getOrNull(playerIndex) ?: return emptyList()
    if (!roller.isBot) {
        return emptyList()
    }
    val humanKillFaces = findCaptureDiceValues(players, playerIndex, humansOnly = true).toSet()
    val anyBotKillFaces = findCaptureDiceValues(players, playerIndex, botsOnly = true).toSet()
    return (anyBotKillFaces - humanKillFaces).toList()
}

private fun findCaptureDiceValues(
    players: List<MatchPlayerState>,
    playerIndex: Int,
    humansOnly: Boolean = false,
    botsOnly: Boolean = false,
): List<Int> {
    val roller = players.getOrNull(playerIndex) ?: return emptyList()
    val killFaces = linkedSetOf<Int>()

    for (dice in 1..6) {
        roller.tokens.forEachIndexed { tokenIndex, progress ->
            if (!canMoveToken(progress, dice)) {
                return@forEachIndexed
            }
            val nextProgress = if (progress == -1) 0 else progress + dice
            if (nextProgress !in 0..MAIN_PATH_LAST_PROGRESS) {
                return@forEachIndexed
            }
            val landingKey = boardCellKey(roller.color, nextProgress, tokenIndex)
            if (ludoSafeCellKeys.contains(landingKey)) {
                return@forEachIndexed
            }

            val wouldCapture = players.indices.any { opponentIndex ->
                if (opponentIndex == playerIndex) {
                    return@any false
                }
                val opponent = players[opponentIndex]
                if (opponent.isEffectivelyAbandoned()) {
                    return@any false
                }
                if (humansOnly && opponent.isBot) {
                    return@any false
                }
                if (botsOnly && !opponent.isBot) {
                    return@any false
                }
                opponent.tokens.indices.any { opponentTokenIndex ->
                    val opponentProgress = opponent.tokens[opponentTokenIndex]
                    opponentProgress in 0..MAIN_PATH_LAST_PROGRESS &&
                        boardCellKey(opponent.color, opponentProgress, opponentTokenIndex) == landingKey
                }
            }

            if (wouldCapture) {
                killFaces.add(dice)
            }
        }
    }

    return killFaces.toList()
}

/** A token that reaches home on an exact face this turn. */
internal data class HomeFinishOption(
    val tokenIndex: Int,
    val dice: Int,
)

/**
 * Tokens of [playerIndex] that land exactly on the home square with a single face.
 */
internal fun findHomeFinishOptions(
    players: List<MatchPlayerState>,
    playerIndex: Int,
): List<HomeFinishOption> {
    val roller = players.getOrNull(playerIndex) ?: return emptyList()
    return roller.tokens.mapIndexedNotNull { tokenIndex, progress ->
        if (progress < 0 || progress >= FINISHED_PROGRESS) {
            return@mapIndexedNotNull null
        }
        val dice = FINISHED_PROGRESS - progress
        if (dice !in 1..6 || !canMoveToken(progress, dice)) {
            return@mapIndexedNotNull null
        }
        HomeFinishOption(tokenIndex = tokenIndex, dice = dice)
    }
}

/**
 * Roll the exact face that brings a token home, naming the token so the move AI
 * cannot spend the face on a capture and leave the token stranded.
 */
private fun rollWithHomeFinishFavor(
    allowSix: Boolean,
    players: List<MatchPlayerState>,
    playerIndex: Int,
    favorPercent: Int,
    random: Random,
): BotDiceDecision? {
    val roller = players.getOrNull(playerIndex) ?: return null
    if (!roller.isBot) {
        return null
    }
    val options = findHomeFinishOptions(players, playerIndex)
        .filter { option -> allowSix || option.dice != 6 }
    if (options.isEmpty()) {
        return null
    }
    if (random.nextInt(100) >= favorPercent) {
        return null
    }
    val chosen = options[random.nextInt(options.size)]
    return BotDiceDecision(dice = chosen.dice, forcedTokenIndex = chosen.tokenIndex)
}

/**
 * Soft bot→bot kill favor: about 25% of the time force a bot-only kill face.
 */
fun rollWithBotVsBotKillFavor(
    allowSix: Boolean,
    players: List<MatchPlayerState>,
    playerIndex: Int,
    random: Random = Random.Default,
): Int? {
    val roller = players.getOrNull(playerIndex) ?: return null
    if (!roller.isBot) {
        return null
    }
    val botOnlyKills = findBotOnlyKillDiceValues(players, playerIndex)
        .filter { face -> allowSix || face != 6 }
    if (botOnlyKills.isEmpty()) {
        return null
    }
    if (random.nextInt(100) >= BOT_VS_BOT_KILL_FAVOR_PERCENT) {
        return null
    }
    return botOnlyKills[random.nextInt(botOnlyKills.size)]
}

private fun resolveAllowSix(
    consecutiveSixCount: Int,
    isBot: Boolean,
    context: DiceRollContext,
): Boolean {
    val nextRollNumber = context.rollerMatchDiceRollCount + 1
    if (!allowsFirstSixOnThisRoll(context.rollerMatchSixCount, nextRollNumber)) {
        return false
    }
    return if (isBot) {
        consecutiveSixCount <= 0
    } else {
        consecutiveSixCount < 2
    }
}

private fun rollWithKillFavor(
    allowSix: Boolean,
    players: List<MatchPlayerState>,
    playerIndex: Int,
    killFavorPercent: Int,
    random: Random,
): Int? {
    val killDice = findKillDiceValues(players, playerIndex)
        .filter { face -> allowSix || face != 6 }

    if (killDice.isNotEmpty() && random.nextInt(100) < killFavorPercent) {
        return killDice[random.nextInt(killDice.size)]
    }
    return null
}

private fun rollWeightedSixOrLow(
    allowSix: Boolean,
    baseSixProbability: Double,
    context: DiceRollContext,
    isBot: Boolean,
    random: Random,
): Int {
    if (!allowSix) {
        return random.nextInt(1, 6)
    }

    val nextRollNumber = context.rollerMatchDiceRollCount + 1
    val balanceNudge = computeSixProbabilityNudge(
        isBot = isBot,
        botMatchSixRolls = context.botMatchSixRolls,
        playerMatchSixRolls = context.playerMatchSixRolls,
        random = random,
    )
    val windowNudge = firstSixWindowNudge(
        rollerMatchSixCount = context.rollerMatchSixCount,
        nextRollNumber = nextRollNumber,
        random = random,
    )
    val sixProbability = (baseSixProbability + balanceNudge + windowNudge)
        .coerceIn(0.0, MAX_SIX_PROBABILITY)

    if (random.nextDouble() < sixProbability) {
        return 6
    }
    return random.nextInt(1, 6)
}

/**
 * Roll dice for a real player with lower kill favor than bots. Uses standard triple-six rule.
 */
fun rollUserDice(
    consecutiveSixCount: Int,
    players: List<MatchPlayerState>,
    playerIndex: Int,
    killFavorPercent: Int,
    context: DiceRollContext = DiceRollContext.DEFAULT,
    random: Random = Random.Default,
): Int {
    val allowSix = resolveAllowSix(consecutiveSixCount, isBot = false, context)
    val favored = rollWithKillFavor(
        allowSix = allowSix,
        players = players,
        playerIndex = playerIndex,
        killFavorPercent = killFavorPercent,
        random = random,
    )
    if (favored != null) {
        return favored
    }

    val baseSixProbability = if (consecutiveSixCount >= 2) 0.0 else 1.0 / 6.0
    return rollWeightedSixOrLow(
        allowSix = allowSix,
        baseSixProbability = baseSixProbability,
        context = context,
        isBot = false,
        random = random,
    )
}

/**
 * Roll dice for a bot with kill favoritism and boosted sixes.
 * Never returns 6 when [consecutiveSixCount] >= 1 (no back-to-back sixes).
 *
 * Kills on real players are staged: instead of snapping to the capture face the
 * bot walks its token in over 2–3 turns, so [stalkPlan] carries the hunt between
 * turns and the returned decision names the token that must be moved.
 *
 * Finishing a token takes priority over opening a new hunt, since matches are
 * won by bringing tokens home.
 */
fun rollBotDice(
    consecutiveSixCount: Int,
    players: List<MatchPlayerState>,
    playerIndex: Int,
    settings: BotDiceSettings = BotDiceSettings.DEFAULT,
    context: DiceRollContext = DiceRollContext.DEFAULT,
    stalkPlan: KillStalkPlan? = null,
    random: Random = Random.Default,
): BotDiceDecision {
    val allowSix = resolveAllowSix(consecutiveSixCount, isBot = true, context)
    // 1) Walk tokens home when an exact face is available. Skipped while a hunt
    //    is in flight so the committed stalk does not lose its thread.
    if (stalkPlan == null) {
        val finishing = rollWithHomeFinishFavor(
            allowSix = allowSix,
            players = players,
            playerIndex = playerIndex,
            favorPercent = BOT_HOME_FINISH_FAVOR_PERCENT,
            random = random,
        )
        if (finishing != null) {
            return finishing
        }
    }
    // 2) Hunt real players across turns (admin %).
    val stalked = resolveStalkDice(
        allowSix = allowSix,
        players = players,
        playerIndex = playerIndex,
        killFavorPercent = settings.botKillFavorFor(players.size),
        existingPlan = stalkPlan,
        random = random,
    )
    if (stalked != null) {
        return stalked
    }
    // 3) Rare soft favor for bot→bot kills (~2–3 of 10).
    val botFavored = rollWithBotVsBotKillFavor(
        allowSix = allowSix,
        players = players,
        playerIndex = playerIndex,
        random = random,
    )
    if (botFavored != null) {
        return BotDiceDecision(dice = botFavored)
    }
    // 4) Otherwise normal weighted six roll (fair-feeling).
    val baseSixProbability = (1.0 / 6.0) * (1.0 + settings.botSixBoostPercent / 100.0)
    return BotDiceDecision(
        dice = rollWeightedSixOrLow(
            allowSix = allowSix,
            baseSixProbability = baseSixProbability,
            context = context,
            isBot = true,
            random = random,
        ),
    )
}
