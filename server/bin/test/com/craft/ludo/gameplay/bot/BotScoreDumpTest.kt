package com.craft.ludo.gameplay.bot

import com.craft.ludo.gameplay.MatchPlayerState
import org.junit.jupiter.api.Test
import kotlin.random.Random

class BotScoreDumpTest {
    @Test
    fun dump() {
        SuperiorBotEngine.weights = BotRewardWeights(expectimaxDepth = 2, maxDecisionMillis = 50)
        val captureCase = listOf(
            MatchPlayerState(
                userId = "b",
                displayName = "Bot",
                color = "green",
                isBot = true,
                tokens = listOf(0, 50, -1, -1),
            ),
            MatchPlayerState(
                userId = "h",
                displayName = "Human",
                color = "yellow",
                isBot = false,
                tokens = listOf(42, -1, -1, -1),
            ),
        )
        SuperiorBotEngine.chooseToken(captureCase, 0, listOf(0, 1), 3, BotDifficulty.HARD, null, Random(12))

        val homeCase = listOf(
            MatchPlayerState(
                userId = "b",
                displayName = "Bot",
                color = "green",
                isBot = true,
                tokens = listOf(50, 5, -1, -1),
            ),
            MatchPlayerState(
                userId = "h",
                displayName = "Human",
                color = "yellow",
                isBot = false,
                tokens = listOf(-1, -1, -1, -1),
            ),
        )
        SuperiorBotEngine.chooseToken(homeCase, 0, listOf(0, 1), 1, BotDifficulty.HARD, null, Random(10))
    }
}
