package com.craft.ludo.gameplay.bot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The offline board in `client/app/components/ludo-shell.js` mirrors the dice
 * bias used by online matches. Nothing at runtime links the two, so this test
 * reads the client constants and fails when one side is tuned without the other.
 *
 * Skipped when the client is not checked out alongside the server.
 */
class BotDiceParityTests {

    private val clientSource: String? by lazy {
        listOf(
            "../client/app/components/ludo-shell.js",
            "client/app/components/ludo-shell.js",
        ).map(::File).firstOrNull(File::isFile)?.readText()
    }

    private fun clientConstant(name: String): Double {
        val source = clientSource ?: error("client source unavailable")
        val match = Regex("""const\s+$name\s*=\s*([0-9]+(?:\.[0-9]+)?)\s*;""").find(source)
            ?: error("client constant $name not found; was it renamed?")
        return match.groupValues[1].toDouble()
    }

    private fun assertMirrors(name: String, serverValue: Number) {
        assumeTrue(clientSource != null, "client source not checked out")
        assertEquals(
            serverValue.toDouble(),
            clientConstant(name),
            "$name drifted between the server and the offline board",
        )
    }

    @Test
    fun `kill favor percentages mirror the offline board`() {
        assertMirrors("BOT_KILL_FAVOR_2P", BotDiceSettings.DEFAULT.botKillFavor2Player)
        assertMirrors("USER_KILL_FAVOR_2P", BotDiceSettings.DEFAULT.userKillFavor2Player)
        assertMirrors("BOT_KILL_FAVOR_MULTI", BotDiceSettings.DEFAULT.botKillFavorMultiPlayer)
        assertMirrors("USER_KILL_FAVOR_MULTI", BotDiceSettings.DEFAULT.userKillFavorMultiPlayer)
    }

    @Test
    fun `six bias mirrors the offline board`() {
        assertMirrors("BOT_SIX_BOOST_PERCENT", BotDiceSettings.DEFAULT.botSixBoostPercent)
        assertMirrors("MAX_SIX_PROBABILITY", MAX_SIX_PROBABILITY)
        assertMirrors("MIN_TOTAL_SIXES_BEFORE_BALANCE", MIN_TOTAL_SIXES_BEFORE_BALANCE)
        assertMirrors("FIRST_SIX_MIN_ROLL_NUMBER", FIRST_SIX_MIN_ROLL_NUMBER)
        assertMirrors("FIRST_SIX_MAX_ROLL_NUMBER", FIRST_SIX_MAX_ROLL_NUMBER)
    }

    @Test
    fun `bot favor constants mirror the offline board`() {
        assertMirrors("BOT_VS_BOT_KILL_FAVOR_PERCENT", BOT_VS_BOT_KILL_FAVOR_PERCENT)
        assertMirrors("BOT_HOME_FINISH_FAVOR_PERCENT", BOT_HOME_FINISH_FAVOR_PERCENT)
    }

    @Test
    fun `stalk pacing mirrors the offline board`() {
        assertMirrors("STALK_INSTANT_KILL_PERCENT", STALK_INSTANT_KILL_PERCENT)
        assertMirrors("STALK_MAX_CHASE_STEPS", STALK_MAX_CHASE_STEPS)
        assertMirrors("STALK_MIN_PLANNED_ROUNDS", STALK_MIN_PLANNED_ROUNDS)
        assertMirrors("STALK_MAX_PLANNED_ROUNDS", STALK_MAX_PLANNED_ROUNDS)
    }
}
