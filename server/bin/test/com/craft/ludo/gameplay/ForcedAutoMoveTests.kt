package com.craft.ludo.gameplay

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ForcedAutoMoveTests {
    @Test
    fun `forces move when only one token is selectable`() {
        assertThat(resolveForcedAutoMoveToken(listOf(0))).isEqualTo(0)
    }

    @Test
    fun `does not force a move on six when yard tokens can also open`() {
        assertThat(resolveForcedAutoMoveToken(listOf(0, 1, 2, 3))).isNull()
    }

    @Test
    fun `does not force a move when multiple tokens are already outside`() {
        assertThat(resolveForcedAutoMoveToken(listOf(0, 1))).isNull()
    }

    @Test
    fun `does not force a move when no token is selectable`() {
        assertThat(resolveForcedAutoMoveToken(emptyList())).isNull()
    }
}
