package com.craft.ludo.gameplay.bot

import com.craft.ludo.gameplay.MatchPlayerState
import com.craft.ludo.gameplay.boardCellKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class BotDiceBiasTests {

    private fun player(
        color: String,
        tokens: List<Int>,
        isBot: Boolean = false,
        matchDiceRollCount: Int = 0,
        matchSixCount: Int = 0,
    ) = MatchPlayerState(
        userId = "u-$color",
        displayName = color,
        color = color,
        isBot = isBot,
        tokens = tokens,
        matchDiceRollCount = matchDiceRollCount,
        matchSixCount = matchSixCount,
    )

    /** Green@1 + dice 3 captures yellow@43 (same board cell). */
    private fun killSetupPlayers() = listOf(
        player("green", listOf(1, 5, -1, -1), isBot = true),
        player("yellow", listOf(43, -1, -1, -1)),
    )

    /** Green@1 needs 9 steps to reach yellow@49, so no single roll can capture. */
    private fun farChasePlayers() = listOf(
        player("green", listOf(1, -1, -1, -1), isBot = true),
        player("yellow", listOf(49, -1, -1, -1)),
    )

    /** Green token 0 sits three steps from home; nothing else can reach it. */
    private fun homeFinishPlayers() = listOf(
        player("green", listOf(53, 5, -1, -1), isBot = true),
        player("yellow", listOf(-1, -1, -1, -1)),
    )

    private fun huntSettings() = BotDiceSettings(botKillFavor2Player = 100, botSixBoostPercent = 0)

    private fun huntContext() = DiceRollContext(rollerMatchDiceRollCount = 1, rollerMatchSixCount = 1)

    private fun withTokenMoved(
        players: List<MatchPlayerState>,
        playerIndex: Int,
        tokenIndex: Int,
        dice: Int,
    ): List<MatchPlayerState> {
        return players.mapIndexed { index, player ->
            if (index != playerIndex) {
                player
            } else {
                val tokens = player.tokens.toMutableList()
                val progress = tokens[tokenIndex]
                tokens[tokenIndex] = if (progress == -1) 0 else progress + dice
                player.copy(tokens = tokens)
            }
        }
    }

    private fun sharesCell(
        players: List<MatchPlayerState>,
        hunterTokenIndex: Int,
        targetTokenIndex: Int,
    ): Boolean {
        val hunter = players[0]
        val target = players[1]
        return boardCellKey(hunter.color, hunter.tokens[hunterTokenIndex], hunterTokenIndex) ==
            boardCellKey(target.color, target.tokens[targetTokenIndex], targetTokenIndex)
    }

    /** Plays bot turns against a stationary target and reports how many it took to capture. */
    private fun runHuntToCapture(
        startingPlayers: List<MatchPlayerState>,
        random: Random,
        maxTurns: Int = 6,
    ): Int? {
        var players = startingPlayers
        var plan: KillStalkPlan? = null

        repeat(maxTurns) { turn ->
            val decision = rollBotDice(
                consecutiveSixCount = 0,
                players = players,
                playerIndex = 0,
                settings = huntSettings(),
                context = huntContext(),
                stalkPlan = plan,
                random = random,
            )
            val tokenIndex = decision.forcedTokenIndex ?: return null
            players = withTokenMoved(players, 0, tokenIndex, decision.dice)
            plan = decision.stalkPlan

            if (sharesCell(players, tokenIndex, 0)) {
                return turn + 1
            }
        }

        return null
    }

    private fun huntPlan(roundsSpent: Int, plannedRounds: Int = 3) = KillStalkPlan(
        hunterPlayerIndex = 0,
        hunterTokenIndex = 0,
        targetPlayerIndex = 1,
        targetTokenIndex = 0,
        plannedRounds = plannedRounds,
        roundsSpent = roundsSpent,
    )

    private fun rollWithPlan(players: List<MatchPlayerState>, plan: KillStalkPlan?, seed: Int) =
        rollBotDice(
            consecutiveSixCount = 0,
            players = players,
            playerIndex = 0,
            settings = huntSettings(),
            context = huntContext(),
            stalkPlan = plan,
            random = Random(seed),
        )

    private fun sixBoostContext() = DiceRollContext(
        rollerMatchDiceRollCount = 2,
        rollerMatchSixCount = 1,
        botMatchSixRolls = 1,
        playerMatchSixRolls = 0,
    )

    @Test
    fun `findKillDiceValues returns distance to opponent on shared path cell`() {
        val kills = findKillDiceValues(killSetupPlayers(), 0)
        assertTrue(kills.contains(3), "expected kill on dice 3, got $kills")
    }

    @Test
    fun `bot kill favor ignores other bots in multi seat games`() {
        val players = listOf(
            player("green", listOf(1, 5, -1, -1), isBot = true),
            player("yellow", listOf(43, -1, -1, -1), isBot = true),
            player("red", listOf(-1, -1, -1, -1)),
        )
        val kills = findKillDiceValues(players, 0)
        assertFalse(kills.contains(3), "bot-vs-bot kill should not count for favor, got $kills")
        assertTrue(findBotOnlyKillDiceValues(players, 0).contains(3))
    }

    @Test
    fun `bot vs bot kill favor lands about 2 to 3 of 10 chances`() {
        val players = listOf(
            player("green", listOf(1, 5, -1, -1), isBot = true),
            player("yellow", listOf(43, -1, -1, -1), isBot = true),
            player("red", listOf(-1, -1, -1, -1)),
        )
        var favored = 0
        repeat(1000) { seed ->
            val face = rollWithBotVsBotKillFavor(
                allowSix = true,
                players = players,
                playerIndex = 0,
                random = Random(seed),
            )
            if (face == 3) favored += 1
        }
        // ~25% ± tolerance
        assertTrue(favored in 180..320, "expected ~250/1000 bot-vs-bot favors, got $favored")
    }

    @Test
    fun `bot kill favor still targets real players in multi seat games`() {
        val players = listOf(
            player("green", listOf(1, 5, -1, -1), isBot = true),
            player("yellow", listOf(43, -1, -1, -1)),
            player("blue", listOf(-1, -1, -1, -1), isBot = true),
        )
        val kills = findKillDiceValues(players, 0)
        assertTrue(kills.contains(3), "bot should favor killing real player, got $kills")
    }

    @Test
    fun `rollBotDice never returns consecutive six`() {
        val players = listOf(
            player("red", listOf(-1, -1, -1, -1), isBot = true, matchDiceRollCount = 2, matchSixCount = 1),
            player("green", listOf(-1, -1, -1, -1)),
        )
        val settings = BotDiceSettings(botKillFavor2Player = 0, botSixBoostPercent = 100)
        repeat(200) {
            val roll = rollBotDice(
                consecutiveSixCount = 1,
                players = players,
                playerIndex = 0,
                settings = settings,
                context = buildDiceRollContext(players, 0),
                random = Random(it),
            ).dice
            assertTrue(roll in 1..5, "got $roll after a six")
        }
    }

    @Test
    fun `first six is blocked on opening roll`() {
        val players = listOf(
            player("red", listOf(-1, -1, -1, -1), isBot = true),
            player("green", listOf(-1, -1, -1, -1)),
        )
        val settings = BotDiceSettings(botKillFavor2Player = 0, botSixBoostPercent = 100)
        repeat(200) { seed ->
            val roll = rollBotDice(
                consecutiveSixCount = 0,
                players = players,
                playerIndex = 0,
                settings = settings,
                context = DiceRollContext(rollerMatchDiceRollCount = 0, rollerMatchSixCount = 0),
                random = Random(seed),
            ).dice
            assertTrue(roll in 1..5, "opening roll should not be six, got $roll")
        }
    }

    @Test
    fun `first six can appear between rolls 2 and 6`() {
        assertFalse(allowsFirstSixOnThisRoll(rollerMatchSixCount = 0, nextRollNumber = 1))
        assertTrue(allowsFirstSixOnThisRoll(rollerMatchSixCount = 0, nextRollNumber = 2))
        assertTrue(allowsFirstSixOnThisRoll(rollerMatchSixCount = 0, nextRollNumber = 6))
        assertTrue(allowsFirstSixOnThisRoll(rollerMatchSixCount = 0, nextRollNumber = 7))
    }

    @Test
    fun `hunting rolls close the gap without overshooting the target`() {
        val random = Random(3)
        val decisions = (0 until 200).map {
            rollBotDice(
                consecutiveSixCount = 0,
                players = killSetupPlayers(),
                playerIndex = 0,
                settings = huntSettings(),
                context = huntContext(),
                random = random,
            )
        }

        assertTrue(
            decisions.all { decision -> decision.dice in 1..3 },
            "hunt rolls should stay within the 3 step gap, got ${decisions.map { it.dice }.distinct()}",
        )
        assertTrue(
            decisions.all { decision -> decision.forcedTokenIndex == 0 },
            "only the token facing the target can be the hunter",
        )
        assertTrue(
            decisions.any { decision -> decision.stalkPlan != null && decision.dice < 3 },
            "bot should sometimes take a partial step instead of the kill face",
        )
    }

    @Test
    fun `most hunts capture over several turns and a few land instantly`() {
        val random = Random(5)
        val trials = 400
        var instantKills = 0

        repeat(trials) {
            val decision = rollBotDice(
                consecutiveSixCount = 0,
                players = killSetupPlayers(),
                playerIndex = 0,
                settings = huntSettings(),
                context = huntContext(),
                random = random,
            )
            if (decision.stalkPlan == null && decision.dice == 3) {
                instantKills += 1
            }
        }

        val rate = instantKills.toDouble() / trials
        assertTrue(
            rate in 0.13..0.33,
            "expected around $STALK_INSTANT_KILL_PERCENT% instant kills, got ${rate * 100}%",
        )
    }

    @Test
    fun `staged hunt still captures the target within a few turns`() {
        val random = Random(11)
        var stagedHunts = 0

        repeat(60) { run ->
            val turns = runHuntToCapture(killSetupPlayers(), random)
            assertNotNull(turns, "hunt $run never captured the target")
            if (turns!! >= 2) {
                stagedHunts += 1
            }
        }

        assertTrue(stagedHunts > 30, "most hunts should span multiple turns, got $stagedHunts of 60")
    }

    /** Green@3 was one step behind yellow@43 before yellow ran 3 forward to 46. */
    private fun targetRanAwayPlayers() = listOf(
        player("green", listOf(3, -1, -1, -1), isBot = true),
        player("yellow", listOf(46, -1, -1, -1)),
    )

    @Test
    fun `hunt re-measures the gap after the target moves between bot turns`() {
        val players = targetRanAwayPlayers()
        assertEquals(
            4,
            stepsToReachCell(players[0], 0, boardCellKey("yellow", 46, 0)),
            "target running 3 forward should widen the gap from 1 to 4",
        )

        val decision = rollWithPlan(players, huntPlan(roundsSpent = 1), seed = 8)

        assertEquals(0, decision.forcedTokenIndex, "bot should keep chasing with the same token")
        assertTrue(decision.dice in 1..4, "roll should suit the widened gap, got ${decision.dice}")
        assertEquals(
            0,
            decision.stalkPlan?.targetTokenIndex ?: 0,
            "the bot should stay locked on the same target",
        )
    }

    @Test
    fun `hunt finishes on the widened gap once the planned rounds run out`() {
        val decision = rollWithPlan(targetRanAwayPlayers(), huntPlan(roundsSpent = 2), seed = 8)

        assertEquals(4, decision.dice, "last planned round should capture across the new gap")
        assertEquals(0, decision.forcedTokenIndex)
        assertEquals(null, decision.stalkPlan, "a finished hunt should not stay committed")
    }

    @Test
    fun `hunt is dropped once the target outruns the chase range`() {
        // Yellow@7 sits 19 steps ahead of green@1, well past the 12 step chase range.
        val players = listOf(
            player("green", listOf(1, -1, -1, -1), isBot = true),
            player("yellow", listOf(7, -1, -1, -1)),
        )
        assertEquals(
            null,
            stepsToReachCell(players[0], 0, boardCellKey("yellow", 7, 0)),
            "target should be beyond chasing range",
        )

        val decision = rollWithPlan(players, huntPlan(roundsSpent = 1), seed = 9)

        assertEquals(null, decision.stalkPlan, "an unreachable target should free the bot")
        assertEquals(null, decision.forcedTokenIndex)
    }

    @Test
    fun `hunt is dropped when the hunter token is sent back to the yard`() {
        val players = listOf(
            player("green", listOf(-1, -1, -1, -1), isBot = true),
            player("yellow", listOf(43, -1, -1, -1)),
        )

        val decision = rollWithPlan(players, huntPlan(roundsSpent = 1), seed = 4)

        assertEquals(null, decision.stalkPlan, "a captured hunter cannot continue the chase")
    }

    @Test
    fun `bot starts closing on a target that no single roll can reach`() {
        val players = farChasePlayers()
        assertTrue(
            findKillDiceValues(players, 0).isEmpty(),
            "target should be out of reach for one roll",
        )

        val decision = rollBotDice(
            consecutiveSixCount = 0,
            players = players,
            playerIndex = 0,
            settings = huntSettings(),
            context = huntContext(),
            random = Random(3),
        )
        val plan = decision.stalkPlan

        assertNotNull(plan, "bot should open a hunt on the distant token")
        assertEquals(0, plan!!.hunterTokenIndex)
        assertEquals(1, plan.targetPlayerIndex)
        assertTrue(decision.dice in 2..5, "approach step should close part of the gap, got ${decision.dice}")
        assertEquals(0, decision.forcedTokenIndex)
    }

    @Test
    fun `a distant chase ends in a capture`() {
        val random = Random(17)
        repeat(30) { run ->
            val turns = runHuntToCapture(farChasePlayers(), random)
            assertNotNull(turns, "distant chase $run never captured the target")
            assertTrue(turns!! >= 2, "a 9 step gap cannot be closed in one turn, took $turns")
        }
    }

    @Test
    fun `targets sitting on a safe cell are not hunted`() {
        // Yellow@13 shares its cell with green@26, which is a safe cell.
        val players = listOf(
            player("green", listOf(24, -1, -1, -1), isBot = true),
            player("yellow", listOf(13, -1, -1, -1)),
        )
        assertEquals(
            boardCellKey("green", 26, 0),
            boardCellKey("yellow", 13, 0),
            "test setup should place both tokens on the same cell path",
        )
        assertTrue(
            findStalkCandidates(players, 0).isEmpty(),
            "a target on a safe cell cannot be captured, so it should not be hunted",
        )
    }

    @Test
    fun `hunts are only started for real players`() {
        val players = listOf(
            player("green", listOf(1, 5, -1, -1), isBot = true),
            player("yellow", listOf(43, -1, -1, -1), isBot = true),
            player("red", listOf(-1, -1, -1, -1)),
        )
        assertTrue(
            findStalkCandidates(players, 0).isEmpty(),
            "bots should not stage hunts against other bots",
        )
    }

    @Test
    fun `rollUserDice favors kill face when userKillFavor is 100`() {
        val players = listOf(
            player("green", listOf(1, 5, -1, -1)),
            player("yellow", listOf(43, -1, -1, -1)),
        )
        val rolls = (0 until 40).map { seed ->
            rollUserDice(
                consecutiveSixCount = 0,
                players = players,
                playerIndex = 0,
                killFavorPercent = 100,
                context = DiceRollContext(rollerMatchDiceRollCount = 1, rollerMatchSixCount = 1),
                random = Random(seed),
            )
        }
        assertTrue(rolls.all { it == 3 }, "expected all kill rolls of 3, got $rolls")
    }

    @Test
    fun `bot six boost increases six rate over fair`() {
        val players = listOf(
            player("red", listOf(-1, -1, -1, -1), isBot = true, matchDiceRollCount = 2, matchSixCount = 1),
            player("green", listOf(-1, -1, -1, -1)),
        )
        val settings = BotDiceSettings(botKillFavor2Player = 0, botSixBoostPercent = 15)
        val trials = 12_000
        var sixes = 0
        repeat(trials) { seed ->
            val roll = rollBotDice(
                consecutiveSixCount = 0,
                players = players,
                playerIndex = 0,
                settings = settings,
                context = sixBoostContext(),
                random = Random(seed),
            ).dice
            if (roll == 6) sixes += 1
        }
        val rate = sixes.toDouble() / trials
        val fair = 1.0 / 6.0
        val expected = fair * 1.15
        assertTrue(rate > fair, "boosted rate $rate should exceed fair $fair")
        assertTrue(
            kotlin.math.abs(rate - expected) < 0.03,
            "boosted rate $rate should be near $expected",
        )
    }

    @Test
    fun `player six nudge increases when bot is ahead`() {
        val nudge = computeSixProbabilityNudge(
            isBot = false,
            botMatchSixRolls = 10,
            playerMatchSixRolls = 2,
            random = Random(42),
        )
        assertTrue(nudge > 0.0, "player should get positive nudge when behind bot")
    }

    @Test
    fun `bot six nudge increases when bot is behind target ratio`() {
        val nudge = computeSixProbabilityNudge(
            isBot = true,
            botMatchSixRolls = 4,
            playerMatchSixRolls = 7,
            random = Random(24),
        )
        assertTrue(nudge > 0.0, "bot should get positive nudge when behind target ratio")
    }

    @Test
    fun `incrementPlayerRollStats tracks dice rolls and sixes`() {
        val players = listOf(
            player("red", listOf(-1, -1, -1, -1), isBot = true),
            player("green", listOf(-1, -1, -1, -1)),
        )
        val updated = incrementPlayerRollStats(players, 0, 6)
        assertEquals(1, updated[0].matchDiceRollCount)
        assertEquals(1, updated[0].matchSixCount)
    }

    @Test
    fun `kill favor resolves by seat count`() {
        val settings = BotDiceSettings(
            botKillFavor2Player = 75,
            userKillFavor2Player = 25,
            botKillFavorMultiPlayer = 60,
            userKillFavorMultiPlayer = 35,
        )
        assertEquals(75, settings.botKillFavorFor(2))
        assertEquals(25, settings.userKillFavorFor(2))
        assertEquals(60, settings.botKillFavorFor(3))
        assertEquals(60, settings.botKillFavorFor(4))
        assertEquals(35, settings.userKillFavorFor(4))
    }

    @Test
    fun `findHomeFinishOptions returns the exact face that reaches home`() {
        val options = findHomeFinishOptions(homeFinishPlayers(), 0)
        assertEquals(listOf(HomeFinishOption(tokenIndex = 0, dice = 3)), options)
    }

    @Test
    fun `findHomeFinishOptions ignores yard and finished tokens`() {
        val players = listOf(
            player("green", listOf(-1, 56, 20, -1), isBot = true),
            player("yellow", listOf(-1, -1, -1, -1)),
        )
        assertTrue(findHomeFinishOptions(players, 0).isEmpty())
    }

    @Test
    fun `bot rolls the exact face that walks a token home`() {
        val settings = BotDiceSettings(botKillFavor2Player = 0, botSixBoostPercent = 0)
        val trials = 4_000
        var finishes = 0
        repeat(trials) { seed ->
            val decision = rollBotDice(
                consecutiveSixCount = 0,
                players = homeFinishPlayers(),
                playerIndex = 0,
                settings = settings,
                context = huntContext(),
                random = Random(seed),
            )
            if (decision.dice == 3 && decision.forcedTokenIndex == 0) {
                finishes += 1
            }
        }
        val rate = finishes.toDouble() / trials
        assertTrue(
            rate > 0.5 && rate < 0.7,
            "expected roughly $BOT_HOME_FINISH_FAVOR_PERCENT% of rolls to finish the token, got $rate",
        )
    }

    @Test
    fun `home finish favor does not interrupt a hunt already in flight`() {
        val players = listOf(
            player("green", listOf(1, 53, -1, -1), isBot = true),
            player("yellow", listOf(43, -1, -1, -1)),
        )
        val plan = huntPlan(roundsSpent = 1)

        repeat(200) { seed ->
            val decision = rollWithPlan(players, plan, seed)
            assertEquals(
                0,
                decision.forcedTokenIndex,
                "committed hunt should keep moving its own token",
            )
        }
    }

    @Test
    fun `defaults slightly favor bot over user`() {
        assertEquals(33, BotDiceSettings.DEFAULT.botKillFavor2Player)
        assertEquals(30, BotDiceSettings.DEFAULT.userKillFavor2Player)
        assertTrue(
            BotDiceSettings.DEFAULT.botKillFavor2Player > BotDiceSettings.DEFAULT.userKillFavor2Player,
        )
    }
}
