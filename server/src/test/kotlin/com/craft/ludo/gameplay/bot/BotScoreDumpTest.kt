package com.craft.ludo.gameplay.bot

import com.craft.ludo.gameplay.MatchPlayerState
import org.junit.jupiter.api.Test
import kotlin.random.Random

class BotScoreDumpTest {
    @Test
    fun dump() {
        SuperiorBotEngine.weights = BotRewardWeights(expectimaxDepth = 2, maxDecisionMillis = 50)
        val captureCase = listOf(
            MatchPlayerState("b", "Bot", "green", true, tokens = listOf(0, 50, -1, -1)),
            MatchPlayerState("h", "Human", "yellow", false, tokens = listOf(42, -1, -1, -1)),
        )
        SuperiorBotEngine.chooseToken(captureCase, 0, listOf(0, 1), 3, BotDifficulty.HARD, null, Random(12))

        val homeCase = listOf(
            MatchPlayerState("b", "Bot", "green", true, tokens = listOf(50, 5, -1, -1)),
            MatchPlayerState("h", "Human", "yellow", false, tokens = listOf(-1, -1, -1, -1)),
        )
        SuperiorBotEngine.chooseToken(homeCase, 0, listOf(0, 1), 1, BotDifficulty.HARD, null, Random(10))
    }
}
