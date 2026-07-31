package com.craft.ludo.gameplay.bot

import com.craft.ludo.gameplay.HOME_LANE_START_PROGRESS
import com.craft.ludo.gameplay.MatchPlayerState
import com.craft.ludo.gameplay.chooseBotToken
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.random.Random

class SuperiorBotEngineTests {
    @BeforeEach
    fun setUp() {
        SuperiorBotEngine.difficulty = BotDifficulty.SUPER
        SuperiorBotEngine.weights = BotRewardWeights()
    }

    @Test
    fun `bot selects immediate winning move`() {
        val players = listOf(
            bot("green", listOf(54, 56, 56, 56)),
            human("yellow", listOf(10, -1, -1, -1)),
        )

        val chosen = SuperiorBotEngine.chooseToken(
            players = players,
            playerIndex = 0,
            movableTokenIndexes = listOf(0),
            diceValue = 2,
            difficultyOverride = BotDifficulty.HARD,
            random = Random(1),
        )

        assertThat(chosen).isEqualTo(0)
    }

    @Test
    fun `bot never selects illegal move outside provided indexes`() {
        val players = listOf(
            bot("green", listOf(1, 5, -1, -1)),
            human("yellow", listOf(-1, -1, -1, -1)),
        )

        assertThat(chooseBotToken(players, 0, listOf(1), 3)).isEqualTo(1)
    }

    @Test
    fun `bot prefers capturing opponent over finishing a single token`() {
        // Same dice=3: finishing token 0 (53+3=56) vs capturing yellow with token 1 (0+3).
        // Kill comes first when the finish does not win the match.
        val players = listOf(
            bot("green", listOf(53, 0, -1, -1)),
            human("yellow", listOf(42, -1, -1, -1)),
        )

        val chosen = SuperiorBotEngine.chooseToken(
            players = players,
            playerIndex = 0,
            movableTokenIndexes = listOf(0, 1),
            diceValue = 3,
            difficultyOverride = BotDifficulty.HARD,
            random = Random(2),
        )

        assertThat(chosen).isEqualTo(1)
    }

    @Test
    fun `bot prefers capturing near-home opponent over plain advance`() {
        // Verified: green progress 0 + dice 3 lands on yellow progress 42 (near home).
        val players = listOf(
            bot("green", listOf(0, 8, -1, -1)),
            human("yellow", listOf(42, -1, -1, -1)),
        )

        val chosen = SuperiorBotEngine.chooseToken(
            players = players,
            playerIndex = 0,
            movableTokenIndexes = listOf(0, 1),
            diceValue = 3,
            difficultyOverride = BotDifficulty.HARD,
            random = Random(3),
        )

        assertThat(chosen).isEqualTo(0)
    }

    @Test
    fun `bot escapes immediate capture threat`() {
        // Verified: green@1 threatened by yellow@34 with dice 6.
        val players = listOf(
            bot("green", listOf(1, 8, -1, -1)),
            human("yellow", listOf(34, -1, -1, -1)),
        )

        val chosen = SuperiorBotEngine.chooseToken(
            players = players,
            playerIndex = 0,
            movableTokenIndexes = listOf(0, 1),
            diceValue = 4,
            difficultyOverride = BotDifficulty.HARD,
            random = Random(5),
        )

        assertThat(chosen).isEqualTo(0)
    }

    @Test
    fun `only one legal move is always returned`() {
        val players = listOf(bot("green", listOf(10, -1, -1, -1)))
        assertThat(chooseBotToken(players, 0, listOf(0), 4)).isEqualTo(0)
    }

    @Test
    fun `all tokens in base opens on six`() {
        val players = listOf(bot("green", listOf(-1, -1, -1, -1)))
        assertThat(chooseBotToken(players, 0, listOf(0, 1, 2, 3), 6)).isIn(0, 1, 2, 3)
    }

    @Test
    fun `simulation does not mutate live state`() {
        val original = listOf(
            bot("green", listOf(1, 5, -1, -1)),
            human("yellow", listOf(43, -1, -1, -1)),
        )
        val snapshot = original.map { it.copy(tokens = it.tokens.toList()) }
        val move = CandidateMove(tokenIndex = 0, fromProgress = 1, toProgress = 4)
        SuperiorBotEngine.simulateMove(original, 0, move, 3)
        assertThat(original).isEqualTo(snapshot)
    }

    @Test
    fun `two-player capture remains preferred with aggression multiplier`() {
        val players = listOf(
            bot("green", listOf(1, 10, -1, -1)),
            human("yellow", listOf(43, -1, -1, -1)),
        )
        val chosen = SuperiorBotEngine.chooseToken(
            players = players,
            playerIndex = 0,
            movableTokenIndexes = listOf(0, 1),
            diceValue = 3,
            difficultyOverride = BotDifficulty.HARD,
            weightsOverride = BotRewardWeights(twoPlayerAttackMultiplier = 1.5),
            random = Random(7),
        )
        assertThat(chosen).isEqualTo(0)
    }

    @Test
    fun `four-player prefers capturing progressed leader`() {
        val players = listOf(
            bot("green", listOf(0, 8, -1, -1)),
            human("yellow", listOf(42, -1, -1, -1)),
            human("red", listOf(2, -1, -1, -1)),
            human("blue", listOf(2, -1, -1, -1)),
        )
        val chosen = SuperiorBotEngine.chooseToken(
            players = players,
            playerIndex = 0,
            movableTokenIndexes = listOf(0, 1),
            diceValue = 3,
            difficultyOverride = BotDifficulty.HARD,
            random = Random(8),
        )
        assertThat(chosen).isEqualTo(0)
    }

    @Test
    fun `expectimax respects computation limits`() {
        val players = listOf(
            bot("green", listOf(10, 20, 30, -1)),
            human("yellow", listOf(5, 15, 25, -1)),
            human("red", listOf(8, 18, -1, -1)),
            human("blue", listOf(9, 19, -1, -1)),
        )
        val started = System.nanoTime()
        SuperiorBotEngine.chooseToken(
            players = players,
            playerIndex = 0,
            movableTokenIndexes = listOf(0, 1, 2),
            diceValue = 4,
            difficultyOverride = BotDifficulty.SUPER,
            weightsOverride = BotRewardWeights(maxDecisionMillis = 5, expectimaxDepth = 2),
            random = Random(9),
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertThat(elapsedMs).isLessThan(500)
    }

    @Test
    fun `completed token states remain valid`() {
        val players = listOf(
            bot("green", listOf(56, 56, 10, -1)),
            human("yellow", listOf(-1, -1, -1, -1)),
        )
        assertThat(chooseBotToken(players, 0, listOf(2), 3)).isEqualTo(2)
    }

    @Test
    fun `home path entry is valued over weak advance`() {
        val players = listOf(
            bot("green", listOf(50, 5, -1, -1)),
            human("yellow", listOf(-1, -1, -1, -1)),
        )
        val chosen = SuperiorBotEngine.chooseToken(
            players = players,
            playerIndex = 0,
            movableTokenIndexes = listOf(0, 1),
            diceValue = 1,
            difficultyOverride = BotDifficulty.HARD,
            random = Random(10),
        )
        assertThat(chosen).isEqualTo(0)
        assertThat(50 + 1).isEqualTo(HOME_LANE_START_PROGRESS)
    }

    @Test
    fun `capture has priority over entering home lane`() {
        // Green token 0 captures yellow@42 with dice=3; token 1 could enter home lane.
        val players = listOf(
            bot("green", listOf(0, 50, -1, -1)),
            human("yellow", listOf(42, -1, -1, -1)),
        )

        val chosen = SuperiorBotEngine.chooseToken(
            players = players,
            playerIndex = 0,
            movableTokenIndexes = listOf(0, 1),
            diceValue = 3,
            difficultyOverride = BotDifficulty.SUPER,
            random = Random(12),
        )

        assertThat(chosen).isEqualTo(0)
    }

    @Test
    fun `finishing token wins unified evaluation over home lane entry`() {
        val players = listOf(
            bot("green", listOf(50, 55, 5, -1)),
            human("yellow", listOf(-1, -1, -1, -1)),
        )

        val chosen = SuperiorBotEngine.chooseToken(
            players = players,
            playerIndex = 0,
            movableTokenIndexes = listOf(0, 1, 2),
            diceValue = 1,
            difficultyOverride = BotDifficulty.SUPER,
            random = Random(13),
        )

        assertThat(chosen).isEqualTo(1)
    }

    @Test
    fun `finishing one token has priority over ordinary progress`() {
        val players = listOf(
            bot("green", listOf(55, 5, -1, -1)),
            human("yellow", listOf(-1, -1, -1, -1)),
        )

        val chosen = SuperiorBotEngine.chooseToken(
            players = players,
            playerIndex = 0,
            movableTokenIndexes = listOf(0, 1),
            diceValue = 1,
            difficultyOverride = BotDifficulty.SUPER,
            random = Random(14),
        )

        assertThat(chosen).isEqualTo(0)
    }

    @Test
    fun `bot hunts valuable opponent reachable in future turns`() {
        // After dice=3, token 0 is seven cells behind yellow@49 and can hunt it
        // over the next two rolls. Token 1 has no forward target.
        val players = listOf(
            bot("green", listOf(0, 8, -1, -1)),
            human("yellow", listOf(49, -1, -1, -1)),
        )

        val chosen = SuperiorBotEngine.chooseToken(
            players = players,
            playerIndex = 0,
            movableTokenIndexes = listOf(0, 1),
            diceValue = 3,
            difficultyOverride = BotDifficulty.HARD,
            weightsOverride = BotRewardWeights(
                huntReward = 1_200.0,
                huntHorizonTurns = 3,
            ),
            random = Random(15),
        )

        assertThat(chosen).isEqualTo(0)
    }

    @Test
    fun `bot develops trailing outside token instead of relying on leader`() {
        val players = listOf(
            bot("green", listOf(0, 20, -1, -1)),
            human("yellow", listOf(-1, -1, -1, -1)),
        )

        val chosen = SuperiorBotEngine.chooseToken(
            players = players,
            playerIndex = 0,
            movableTokenIndexes = listOf(0, 1),
            diceValue = 3,
            difficultyOverride = BotDifficulty.HARD,
            weightsOverride = BotRewardWeights(tokenDiversityReward = 400.0),
            random = Random(16),
        )

        assertThat(chosen).isEqualTo(0)
    }

    @Test
    fun `easy difficulty still returns a legal token`() {
        val players = listOf(bot("green", listOf(3, 9, -1, -1)))
        val chosen = SuperiorBotEngine.chooseToken(
            players = players,
            playerIndex = 0,
            movableTokenIndexes = listOf(0, 1),
            diceValue = 2,
            difficultyOverride = BotDifficulty.EASY,
            random = Random(11),
        )
        assertThat(chosen).isIn(0, 1)
    }

    private fun bot(color: String, tokens: List<Int>) = MatchPlayerState(
        userId = "bot-$color",
        displayName = "Bot-$color",
        color = color,
        isBot = true,
        tokens = tokens,
    )

    private fun human(color: String, tokens: List<Int>) = MatchPlayerState(
        userId = "human-$color",
        displayName = "Human-$color",
        color = color,
        isBot = false,
        tokens = tokens,
    )
}
