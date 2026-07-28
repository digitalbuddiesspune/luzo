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
        assertThat(outcome.botWinner).isNull()
        assertThat(outcome.houseWin).isFalse()
        assertThat(countActiveRealHumans(after)).isEqualTo(3)
    }

    @Test
    fun `three human one bot match continues after one leave`() {
        val before = listOf(
            humanPlayer("a", "Alice"),
            humanPlayer("b", "Bob"),
            humanPlayer("c", "Cara"),
            botPlayer("bot-red", "Red Bot"),
        )
        val after = listOf(
            humanPlayer("a", "Alice"),
            abandonedHumanPlayer("Bob"),
            humanPlayer("c", "Cara"),
            botPlayer("bot-red", "Red Bot"),
        )

        val outcome = resolveAbandonOutcome(before, after)

        assertThat(outcome.forfeitWinner).isNull()
        assertThat(outcome.botWinner).isNull()
        assertThat(outcome.houseWin).isFalse()
        assertThat(countActiveRealHumans(after)).isEqualTo(2)
    }

    @Test
    fun `two human two bot match continues after one human leaves`() {
        val before = listOf(
            humanPlayer("a", "Alice"),
            humanPlayer("b", "Bob"),
            botPlayer("bot-red", "Red Bot"),
            botPlayer("bot-yellow", "Yellow Bot"),
        )
        val after = listOf(
            humanPlayer("a", "Alice"),
            abandonedHumanPlayer("Bob"),
            botPlayer("bot-red", "Red Bot"),
            botPlayer("bot-yellow", "Yellow Bot"),
        )

        val outcome = resolveAbandonOutcome(before, after)

        assertThat(outcome.forfeitWinner).isNull()
        assertThat(outcome.botWinner).isNull()
        assertThat(outcome.houseWin).isFalse()
        assertThat(countActiveRealHumans(after)).isEqualTo(1)
    }

    @Test
    fun `last real player leave declares leading bot the winner`() {
        val before = listOf(
            humanPlayer("a", "Alice"),
            botPlayer("bot-red", "Red Bot", tokens = listOf(10, 5, -1, -1)),
            botPlayer("bot-yellow", "Yellow Bot", tokens = listOf(40, 20, 10, 5)),
            botPlayer("bot-blue", "Blue Bot", tokens = listOf(1, -1, -1, -1)),
        )
        val after = listOf(
            abandonedHumanPlayer("Alice"),
            botPlayer("bot-red", "Red Bot", tokens = listOf(10, 5, -1, -1)),
            botPlayer("bot-yellow", "Yellow Bot", tokens = listOf(40, 20, 10, 5)),
            botPlayer("bot-blue", "Blue Bot", tokens = listOf(1, -1, -1, -1)),
        )

        val outcome = resolveAbandonOutcome(before, after)

        assertThat(outcome.forfeitWinner).isNull()
        assertThat(outcome.houseWin).isFalse()
        assertThat(outcome.botWinner?.userId).isEqualTo("bot-yellow")
        assertThat(outcome.botWinner?.displayName).isEqualTo("Yellow Bot")
        assertThat(shouldEndMatchWithNoHumans(after)).isTrue()
    }

    @Test
    fun `last human leave with one bot declares that bot the winner`() {
        val before = listOf(
            humanPlayer("a", "Alice"),
            abandonedHumanPlayer("Bob"),
            abandonedHumanPlayer("Cara"),
            botPlayer("bot-red", "Red Bot"),
        )
        val after = listOf(
            abandonedHumanPlayer("Alice"),
            abandonedHumanPlayer("Bob"),
            abandonedHumanPlayer("Cara"),
            botPlayer("bot-red", "Red Bot"),
        )

        val outcome = resolveAbandonOutcome(before, after)

        assertThat(outcome.forfeitWinner).isNull()
        assertThat(outcome.houseWin).isFalse()
        assertThat(outcome.botWinner?.userId).isEqualTo("bot-red")
    }

    @Test
    fun `last human leave with no bots awards house win`() {
        val before = listOf(
            humanPlayer("a", "Alice"),
            abandonedHumanPlayer("Bob"),
            abandonedHumanPlayer("Cara"),
            abandonedHumanPlayer("Dev"),
        )
        val after = listOf(
            abandonedHumanPlayer("Alice"),
            abandonedHumanPlayer("Bob"),
            abandonedHumanPlayer("Cara"),
            abandonedHumanPlayer("Dev"),
        )

        val outcome = resolveAbandonOutcome(before, after)

        assertThat(outcome.forfeitWinner).isNull()
        assertThat(outcome.botWinner).isNull()
        assertThat(outcome.houseWin).isTrue()
    }

    @Test
    fun `selectWinningBot prefers highest progress then stable userId tie break`() {
        val bots = listOf(
            botPlayer("bot-blue", "Blue Bot", tokens = listOf(20, 20, -1, -1)),
            botPlayer("bot-red", "Red Bot", tokens = listOf(20, 20, -1, -1)),
            botPlayer("bot-yellow", "Yellow Bot", tokens = listOf(5, -1, -1, -1)),
        )

        // Equal progress → higher userId wins ("bot-red" > "bot-blue").
        assertThat(selectWinningBot(bots)?.userId).isEqualTo("bot-red")
        assertThat(botProgressScore(bots[0])).isEqualTo(40)
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

    private fun botPlayer(
        userId: String,
        displayName: String,
        tokens: List<Int> = listOf(-1, -1, -1, -1),
    ): MatchPlayerState {
        return MatchPlayerState(
            userId = userId,
            displayName = displayName,
            color = "red",
            isBot = true,
            isAbandoned = false,
            tokens = tokens,
        )
    }
}
