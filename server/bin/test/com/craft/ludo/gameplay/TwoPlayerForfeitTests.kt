package com.craft.ludo.gameplay

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TwoPlayerForfeitTests {
    @Test
    fun `awards forfeit win when one of two human players leaves`() {
        val playersBeforeLeave = listOf(
            humanPlayer("player-a", "Alice"),
            humanPlayer("player-b", "Bob"),
        )
        val playersAfterLeave = listOf(
            humanPlayer("player-a", "Alice"),
            abandonedHumanPlayer("Bob"),
        )

        val winner = resolveTwoPlayerForfeitWinner(playersBeforeLeave, playersAfterLeave)

        assertThat(winner?.userId).isEqualTo("player-a")
        assertThat(winner?.displayName).isEqualTo("Alice")
    }

    @Test
    fun `does not award forfeit win when more than two humans remain active`() {
        val playersBeforeLeave = listOf(
            humanPlayer("player-a", "Alice"),
            humanPlayer("player-b", "Bob"),
            humanPlayer("player-c", "Cara"),
        )
        val playersAfterLeave = listOf(
            humanPlayer("player-a", "Alice"),
            abandonedHumanPlayer("Bob"),
            humanPlayer("player-c", "Cara"),
        )

        assertThat(resolveTwoPlayerForfeitWinner(playersBeforeLeave, playersAfterLeave)).isNull()
    }

    @Test
    fun `does not forfeit when one of two humans leaves a four seat match with bots`() {
        val playersBeforeLeave = listOf(
            humanPlayer("player-a", "Alice"),
            humanPlayer("player-b", "Bob"),
            botPlayer("bot-red", "Red Bot"),
            botPlayer("bot-yellow", "Yellow Bot"),
        )
        val playersAfterLeave = listOf(
            humanPlayer("player-a", "Alice"),
            abandonedHumanPlayer("Bob"),
            botPlayer("bot-red", "Red Bot"),
            botPlayer("bot-yellow", "Yellow Bot"),
        )

        assertThat(resolveTwoPlayerForfeitWinner(playersBeforeLeave, playersAfterLeave)).isNull()
        val outcome = resolveAbandonOutcome(playersBeforeLeave, playersAfterLeave)
        assertThat(outcome.forfeitWinner).isNull()
        assertThat(outcome.botWinner).isNull()
        assertThat(outcome.houseWin).isFalse()
    }

    @Test
    fun `awards remaining bot the win in a two seat human vs bot match`() {
        val playersBeforeLeave = listOf(
            humanPlayer("player-a", "Alice"),
            botPlayer("bot-red", "Red Bot"),
        )
        val playersAfterLeave = listOf(
            abandonedHumanPlayer("Alice"),
            botPlayer("bot-red", "Red Bot"),
        )

        val winner = resolveTwoPlayerForfeitWinner(playersBeforeLeave, playersAfterLeave)

        assertThat(winner?.userId).isEqualTo("bot-red")
        assertThat(winner?.displayName).isEqualTo("Red Bot")
    }

    private fun humanPlayer(userId: String, displayName: String): MatchPlayerState {
        return MatchPlayerState(
            userId = userId,
            displayName = displayName,
            color = "blue",
            isBot = false,
            isAbandoned = false,
            tokens = listOf(-1, -1, -1, -1),
        )
    }

    private fun abandonedHumanPlayer(displayName: String): MatchPlayerState {
        return MatchPlayerState(
            userId = "abandoned_test",
            displayName = displayName,
            color = "green",
            isBot = false,
            isAbandoned = true,
            tokens = emptyList(),
        )
    }

    private fun botPlayer(userId: String, displayName: String): MatchPlayerState {
        return MatchPlayerState(
            userId = userId,
            displayName = displayName,
            color = "red",
            isBot = true,
            isAbandoned = false,
            tokens = listOf(-1, -1, -1, -1),
        )
    }
}
