package com.craft.ludo.gameplay

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BotDisplayNameTests {
    private val usernamePool: List<String> = requireNotNull(
        javaClass.classLoader.getResourceAsStream("bot-usernames.txt"),
    ) { "bot-usernames.txt must be packaged as a resource" }
        .bufferedReader()
        .useLines { lines -> lines.map { it.trim() }.filter { it.isNotEmpty() }.toList() }

    @Test
    fun `username pool is loaded from resources`() {
        assertThat(usernamePool).hasSizeGreaterThan(100)
        assertThat(usernamePool).contains("Aarav101")
    }

    @Test
    fun `bot display name comes from the username pool`() {
        repeat(50) {
            assertThat(botDisplayName()).isIn(usernamePool)
        }
    }

    @Test
    fun `bot display name is not a legacy hardcoded name`() {
        val legacyNames = setOf("Aarav", "Meera", "Kabir")
        repeat(50) {
            assertThat(botDisplayName()).isNotIn(legacyNames)
        }
    }

    @Test
    fun `bot display name avoids already used names`() {
        val used = mutableListOf<String>()
        repeat(3) {
            val name = botDisplayName(used)
            assertThat(name).isNotIn(used)
            used += name
        }
    }
}
