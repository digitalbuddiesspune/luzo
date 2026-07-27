package com.craft.ludo.gameplay

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TwoPlayerDiagonalSeatTests {
    @Test
    fun `two player seat colors are always a diagonal pair`() {
        repeat(20) {
            val colors = assignSeatColors(2)

            assertThat(colors).hasSize(2)
            assertThat(setOf(colors[0], colors[1])).isIn(
                setOf("red", "yellow"),
                setOf("green", "blue"),
            )
        }
    }

    @Test
    fun `diagonal opposite maps each house to the far corner`() {
        assertThat(diagonalOppositeColor("red")).isEqualTo("yellow")
        assertThat(diagonalOppositeColor("yellow")).isEqualTo("red")
        assertThat(diagonalOppositeColor("green")).isEqualTo("blue")
        assertThat(diagonalOppositeColor("blue")).isEqualTo("green")
    }

    @Test
    fun `four player seat colors still use all houses`() {
        val colors = assignSeatColors(4)

        assertThat(colors).containsExactlyInAnyOrder("red", "green", "yellow", "blue")
    }
}
