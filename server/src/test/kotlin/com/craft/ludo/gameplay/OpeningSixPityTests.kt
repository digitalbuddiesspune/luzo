package com.craft.ludo.gameplay

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpeningSixPityTests {

    @Test
    fun `needs opening six only when yard tokens exist and none are on the board`() {
        assertTrue(needsOpeningSix(player(listOf(-1, -1, -1, -1))))
        assertTrue(needsOpeningSix(player(listOf(-1, -1, FINISHED_PROGRESS, FINISHED_PROGRESS))))
        assertFalse(needsOpeningSix(player(listOf(0, -1, -1, -1))))
        assertFalse(needsOpeningSix(player(listOf(FINISHED_PROGRESS, FINISHED_PROGRESS, FINISHED_PROGRESS, FINISHED_PROGRESS))))
    }

    @Test
    fun `pity guarantees a six after failed open rolls for human or bot`() {
        repeat(50) {
            val dice = randomDice(
                consecutiveSixCount = 0,
                needsOpeningSix = true,
                consecutiveFailedOpenRolls = 3,
                openingSixPityAfterRolls = 3,
                openingSixSoftBoost = false,
            )
            assertEquals(6, dice)
        }
    }

    @Test
    fun `failed open counter increments until six then resets`() {
        val stuck = player(listOf(-1, -1, -1, -1), consecutiveFailedOpenRolls = 2)
        assertEquals(3, nextConsecutiveFailedOpenRolls(stuck, dice = 4))
        assertEquals(0, nextConsecutiveFailedOpenRolls(stuck, dice = 6))

        val onBoard = player(listOf(3, -1, -1, -1), consecutiveFailedOpenRolls = 5)
        assertEquals(0, nextConsecutiveFailedOpenRolls(onBoard, dice = 2))
    }

    @Test
    fun `soft boost raises six rate while stuck in yard`() {
        var sixes = 0
        val samples = 14_000
        repeat(samples) {
            val dice = randomDice(
                needsOpeningSix = true,
                consecutiveFailedOpenRolls = 0,
                openingSixPityAfterRolls = 99,
                openingSixSoftBoost = true,
            )
            if (dice == 6) sixes += 1
        }
        val rate = sixes.toDouble() / samples
        // Expected ~2/7 ≈ 0.286; allow sampling noise.
        assertTrue(rate in 0.24..0.34, "soft-boost six rate was $rate")
    }

    private fun player(
        tokens: List<Int>,
        consecutiveFailedOpenRolls: Int = 0,
    ) = MatchPlayerState(
        userId = "u1",
        displayName = "Player",
        color = "red",
        isBot = false,
        tokens = tokens,
        consecutiveFailedOpenRolls = consecutiveFailedOpenRolls,
    )
}
