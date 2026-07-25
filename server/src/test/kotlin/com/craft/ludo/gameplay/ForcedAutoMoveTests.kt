package com.craft.ludo.gameplay

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ForcedAutoMoveTests {
    @Test
    fun `forces move when only one token is selectable`() {
        val player = player(tokens = listOf(4, -1, -1, -1))

        assertThat(resolveForcedAutoMoveToken(player, listOf(0))).isEqualTo(0)
    }

    @Test
    fun `forces the sole outside token even when yard tokens are also selectable`() {
        val player = player(tokens = listOf(4, -1, -1, -1))

        assertThat(resolveForcedAutoMoveToken(player, listOf(0, 1, 2, 3))).isEqualTo(0)
    }

    @Test
    fun `does not force a move when multiple tokens are already outside`() {
        val player = player(tokens = listOf(4, 8, -1, -1))

        assertThat(resolveForcedAutoMoveToken(player, listOf(0, 1))).isNull()
    }

    @Test
    fun `does not force a move when no token is selectable`() {
        val player = player(tokens = listOf(4, -1, -1, -1))

        assertThat(resolveForcedAutoMoveToken(player, emptyList())).isNull()
    }

    private fun player(tokens: List<Int>) = MatchPlayerState(
        userId = "user-1",
        displayName = "Player",
        color = "red",
        isBot = false,
        tokens = tokens,
    )
}
