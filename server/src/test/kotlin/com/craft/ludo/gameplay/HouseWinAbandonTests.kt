package com.craft.ludo.gameplay

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HouseWinAbandonTests {
    @Test
    fun `four human match continues after one leave`() {
        val before = listOf(
            humanPlayer("a", "Alice"),
            humanPlayer("b", "Bob"),
            humanPlayer("c", "Cara"),
            humanPlayer("d", "Dev"),
        )
        val after = listOf(
            humanPlayer("a", "Alice"),
            abandonedHumanPlayer("Bob"),
            humanPlayer("c", "Cara"),
            humanPlayer("d", "Dev"),
        )

        val outcome = resolveAbandonOutcome(before, after)

        assertThat(outcome.forfeitWinner).isNull()
        assertThat(outcome.houseWin).isFalse()
        assertThat(countActiveRealHumans(after)).isEqualTo(3)
    }

    @Test
    fun `three human match continues after one leave`() {
        val before = listOf(
            humanPlayer("a", "Alice"),
            humanPlayer("b", "Bob"),
            humanPlayer("c", "Cara"),
        )
        val after = listOf(
            humanPlayer("a", "Alice"),
            abandonedHumanPlayer("Bob"),
            humanPlayer("c", "Cara"),
        )

        val outcome = resolveAbandonOutcome(before, after)

        assertThat(outcome.forfeitWinner).isNull()
        assertThat(outcome.houseWin).isFalse()
        assertThat(countActiveRealHumans(after)).isEqualTo(2)
    }

    @Test
    fun `last real player leave awards house win while bots remain`() {
        val before = listOf(
            humanPlayer("a", "Alice"),
            botPlayer("bot-red", "Red Bot"),
            botPlayer("bot-yellow", "Yellow Bot"),
            botPlayer("bot-blue", "Blue Bot"),
        )
        val after = listOf(
            abandonedHumanPlayer("Alice"),
            botPlayer("bot-red", "Red Bot"),
            botPlayer("bot-yellow", "Yellow Bot"),
            botPlayer("bot-blue", "Blue Bot"),
        )

        val outcome = resolveAbandonOutcome(before, after)

        assertThat(outcome.forfeitWinner).isNull()
        assertThat(outcome.houseWin).isTrue()
        assertThat(shouldAwardHouseWin(after)).isTrue()
    }

    @Test
    fun `two human forfeit still wins over house win`() {
        val before = listOf(
            humanPlayer("a", "Alice"),
            humanPlayer("b", "Bob"),
            botPlayer("bot-red", "Red Bot"),
        )
        val after = listOf(
            humanPlayer("a", "Alice"),
            abandonedHumanPlayer("Bob"),
            botPlayer("bot-red", "Red Bot"),
        )

        val outcome = resolveAbandonOutcome(before, after)

        assertThat(outcome.forfeitWinner?.userId).isEqualTo("a")
        assertThat(outcome.houseWin).isFalse()
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
