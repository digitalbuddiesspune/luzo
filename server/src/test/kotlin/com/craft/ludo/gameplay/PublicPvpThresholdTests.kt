package com.craft.ludo.gameplay

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PublicPvpThresholdTests {
    @Test
    fun `allows public pvp by default so the first waiting player can be joined`() {
        assertThat(allowsPublicPvpMatchmaking(waitingRealPlayerCount = 0)).isTrue()
        assertThat(allowsPublicPvpMatchmaking(waitingRealPlayerCount = 1)).isTrue()
    }

    @Test
    fun `blocks public pvp while resulting waiting population is at or below a custom threshold`() {
        assertThat(
            allowsPublicPvpMatchmaking(waitingRealPlayerCount = 0, threshold = 25),
        ).isFalse()
        assertThat(
            allowsPublicPvpMatchmaking(waitingRealPlayerCount = 1, threshold = 25),
        ).isFalse()
        assertThat(
            allowsPublicPvpMatchmaking(waitingRealPlayerCount = 24, threshold = 25),
        ).isFalse()
    }

    @Test
    fun `allows public pvp once joining user pushes waiting population above a custom threshold`() {
        assertThat(
            allowsPublicPvpMatchmaking(waitingRealPlayerCount = 25, threshold = 25),
        ).isTrue()
    }
}
