package com.craft.ludo.gameplay

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MissedTurnAutoLeaveTests {
    @Test
    fun `increments missed turns without leaving before the limit`() {
        val players = listOf(
            humanPlayer("player-a", "Alice", missedTurns = 0),
            humanPlayer("player-b", "Bob"),
        )

        val result = registerMissedTurn(
            players = players,
            playerIndex = 0,
            maxMissedTurns = 2,
            abandonedUserId = "abandoned_test",
        )

        assertThat(result.autoLeft).isFalse()
        assertThat(result.abandonOutcome.forfeitWinner).isNull()
        assertThat(result.abandonOutcome.houseWin).isFalse()
        assertThat(result.players[0].missedTurns).isEqualTo(1)
        assertThat(result.players[0].isAbandoned).isFalse()
        assertThat(result.players[0].userId).isEqualTo("player-a")
    }

    @Test
    fun `auto leaves after two missed turns in the whole match`() {
        val players = listOf(
            humanPlayer("player-a", "Alice", missedTurns = 1),
            humanPlayer("player-b", "Bob"),
            humanPlayer("player-c", "Cara"),
        )

        val result = registerMissedTurn(
            players = players,
            playerIndex = 0,
            maxMissedTurns = 2,
            abandonedUserId = "abandoned_test",
        )

        assertThat(result.autoLeft).isTrue()
        assertThat(result.abandonOutcome.forfeitWinner).isNull()
        assertThat(result.abandonOutcome.houseWin).isFalse()
        assertThat(result.players[0].isAbandoned).isTrue()
        assertThat(result.players[0].userId).isEqualTo("abandoned_test")
        assertThat(result.players[0].tokens).isEmpty()
        assertThat(result.players[0].missedTurns).isEqualTo(2)
    }

    @Test
    fun `awards remaining human the win in a two player match after two misses`() {
        val players = listOf(
            humanPlayer("player-a", "Alice", missedTurns = 1),
            humanPlayer("player-b", "Bob"),
        )

        val result = registerMissedTurn(
            players = players,
            playerIndex = 0,
            maxMissedTurns = 2,
            abandonedUserId = "abandoned_test",
        )

        assertThat(result.autoLeft).isTrue()
        assertThat(result.abandonOutcome.forfeitWinner?.userId).isEqualTo("player-b")
        assertThat(result.abandonOutcome.forfeitWinner?.displayName).isEqualTo("Bob")
        assertThat(result.abandonOutcome.houseWin).isFalse()
    }

    @Test
    fun `awards bot win when last real player is removed for missed turns`() {
        val players = listOf(
            humanPlayer("player-a", "Alice", missedTurns = 1),
            botPlayer("bot-red", "Red Bot"),
            botPlayer("bot-yellow", "Yellow Bot"),
        )

        val result = registerMissedTurn(
            players = players,
            playerIndex = 0,
            maxMissedTurns = 2,
            abandonedUserId = "abandoned_test",
        )

        assertThat(result.autoLeft).isTrue()
        assertThat(result.abandonOutcome.forfeitWinner).isNull()
        assertThat(result.abandonOutcome.houseWin).isFalse()
        assertThat(result.abandonOutcome.botWinner?.userId).isIn("bot-red", "bot-yellow")
    }

    @Test
    fun `keeps miss count after earlier successful play would have reset consecutive logic`() {
        val players = listOf(
            humanPlayer("player-a", "Alice", missedTurns = 1),
            humanPlayer("player-b", "Bob"),
        )

        // A second miss anywhere in the match removes the player — counts are total, not consecutive.
        val result = registerMissedTurn(
            players = players,
            playerIndex = 0,
            maxMissedTurns = 2,
            abandonedUserId = "abandoned_test",
        )

        assertThat(result.autoLeft).isTrue()
        assertThat(result.players[0].missedTurns).isEqualTo(2)
    }

    private fun humanPlayer(
        userId: String,
        displayName: String,
        missedTurns: Int = 0,
    ): MatchPlayerState {
        return MatchPlayerState(
            userId = userId,
            displayName = displayName,
            color = "blue",
            isBot = false,
            isAbandoned = false,
            tokens = listOf(-1, -1, -1, -1),
            missedTurns = missedTurns,
        )
    }

    private fun botPlayer(
        userId: String,
        displayName: String,
    ): MatchPlayerState {
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
