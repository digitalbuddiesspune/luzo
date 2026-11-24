package com.craft.ludo.gameplay

import com.craft.ludo.wallet.DEFAULT_GAME_LEVELS
import com.craft.ludo.wallet.GameLevelConfig
import com.craft.ludo.wallet.calculateLevelPrizePool
import com.craft.ludo.wallet.normalizeGameLevels
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GameLevelMatchmakingTests {
    @Test
    fun `default game levels include seven tiers supporting 2p and 4p`() {
        val levels = normalizeGameLevels(null)
        assertEquals(7, levels.size)
        assertEquals(listOf(100L, 200L, 500L, 1000L, 2000L, 3000L, 5000L), levels.map { it.entryFee })
        assertTrue(levels.all { it.playerModes == listOf(2, 4) && it.enabled })
    }

    @Test
    fun `prize pool deducts platform fee per seat for both 2p and 4p`() {
        assertEquals(180L, calculateLevelPrizePool(entryFee = 100L, maxPlayers = 2, platformFeePerPlayer = 10L))
        assertEquals(360L, calculateLevelPrizePool(entryFee = 100L, maxPlayers = 4, platformFeePerPlayer = 10L))
        assertEquals(980L, calculateLevelPrizePool(entryFee = 500L, maxPlayers = 2, platformFeePerPlayer = 10L))
        assertEquals(1960L, calculateLevelPrizePool(entryFee = 500L, maxPlayers = 4, platformFeePerPlayer = 10L))
        assertEquals(0L, calculateLevelPrizePool(entryFee = 5L, maxPlayers = 2, platformFeePerPlayer = 10L))
    }

    @Test
    fun `normalizeGameLevels sorts by sortOrder and filters invalid modes`() {
        val normalized = normalizeGameLevels(
            listOf(
                GameLevelConfig(id = "high", name = "High", entryFee = 500, playerModes = listOf(4, 99), sortOrder = 2),
                GameLevelConfig(id = "low", name = "Low", entryFee = 50, playerModes = listOf(2), sortOrder = 1),
            ),
        )
        assertEquals(listOf("low", "high"), normalized.map { it.id })
        assertEquals(listOf(2), normalized[0].playerModes)
        assertEquals(listOf(4), normalized[1].playerModes)
        assertEquals(DEFAULT_GAME_LEVELS.size, normalizeGameLevels(emptyList()).size)
    }
}
