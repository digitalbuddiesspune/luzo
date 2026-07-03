package com.craft.ludo.gameplay

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PublicPvpThresholdTests {
    @Test
    fun `blocks public pvp while resulting waiting population is 25 or below`() {
        assertThat(allowsPublicPvpMatchmaking(waitingRealPlayerCount = 0)).isFalse()
        assertThat(allowsPublicPvpMatchmaking(waitingRealPlayerCount = 1)).isFalse()
        assertThat(allowsPublicPvpMatchmaking(waitingRealPlayerCount = 24)).isFalse()
    }

    @Test
    fun `allows public pvp once joining user pushes waiting population above 25`() {
        assertThat(allowsPublicPvpMatchmaking(waitingRealPlayerCount = 25)).isTrue()
    }
}
