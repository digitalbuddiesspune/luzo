package com.craft.ludo.gameplay

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BotMoveStrategyTests {
    @Test
    fun `bot prefers capturing an opponent over a plain advance`() {
        val players = listOf(
            MatchPlayerState(
                userId = "bot",
                displayName = "Aarav",
                color = "green",
                isBot = true,
                tokens = listOf(1, 5, -1, -1),
            ),
            MatchPlayerState(
                userId = "human",
                displayName = "Player",
                color = "yellow",
                isBot = false,
                tokens = listOf(43, -1, -1, -1),
            ),
        )

        val chosenToken = chooseBotToken(
            players = players,
            playerIndex = 0,
            movableTokenIndexes = listOf(0, 1),
            diceValue = 3,
        )

        assertThat(chosenToken).isEqualTo(0)
    }

    @Test
    fun `bot prefers finishing a token over advancing another`() {
        val players = listOf(
            MatchPlayerState(
                userId = "bot",
                displayName = "Aarav",
                color = "green",
                isBot = true,
                tokens = listOf(54, 10, -1, -1),
            ),
        )

        val chosenToken = chooseBotToken(
            players = players,
            playerIndex = 0,
            movableTokenIndexes = listOf(0, 1),
            diceValue = 2,
        )

        assertThat(chosenToken).isEqualTo(0)
    }

    @Test
    fun `bot opens from yard when no token is on the board`() {
        val players = listOf(
            MatchPlayerState(
                userId = "bot",
                displayName = "Aarav",
                color = "green",
                isBot = true,
                tokens = listOf(-1, -1, -1, -1),
            ),
        )

        val chosenToken = chooseBotToken(
            players = players,
            playerIndex = 0,
            movableTokenIndexes = listOf(0, 1, 2, 3),
            diceValue = 6,
        )

        assertThat(chosenToken).isIn(0, 1, 2, 3)
    }
}
