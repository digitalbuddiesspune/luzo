package com.craft.ludo.gameplay

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MatchSnapshotSerializationTests {
    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `match player state serializes abandoned flag with client field name`() {
        val player = MatchPlayerState(
            userId = "abandoned-123",
            displayName = "Left Player",
            color = "red",
            isBot = false,
            isAbandoned = true,
            tokens = emptyList(),
        )

        val json = objectMapper.writeValueAsString(player)

        assertThat(json).contains("\"isBot\":false")
        assertThat(json).contains("\"isAbandoned\":true")
    }
}
