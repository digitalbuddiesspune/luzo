package com.craft.ludo.tournament

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class TournamentBracketTests {
    private val planner = TournamentBracketService()
    @Test fun `sixteen two player entrants play eight four two one matches`() {
        assertThat(planner.preview(16, 2).map { it.qualifiers.size }).containsExactly(8, 4, 2, 1)
    }
    @Test fun `four first round winners go directly to four player final`() {
        assertThat(planner.preview(16, 4).map { it.qualifiers.size }).containsExactly(4, 1)
    }
    @Test fun `every supported capacity terminates with one champion without dropping byes`() {
        for (format in listOf(2, 4)) for (capacity in format..512) {
            val rounds = planner.preview(capacity, format)
            assertThat(rounds.last().advancing).isEqualTo(1)
            rounds.forEach { round ->
                assertThat(round.qualifiers.size * format + round.byes).isEqualTo(round.players)
                assertThat(round.advancing).isLessThan(round.players)
                assertThat(round.qualifiers).allMatch { it in 1..format }
            }
        }
    }
    @Test fun `eight four player entrants receive enough qualifiers to fill a final`() {
        assertThat(planner.preview(8, 4).first().qualifiers).containsExactly(2, 2)
    }
    @Test fun `invalid qualification policy is rejected before publishing`() {
        assertThatThrownBy { planner.preview(8, 4, listOf(1)) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { planner.preview(4, 4, listOf(2)) }.isInstanceOf(IllegalArgumentException::class.java)
    }
    @Test fun `bracket generates once with distinct deterministic match ids`() {
        val now = Instant.parse("2026-11-20T00:00:00Z")
        val config = TournamentConfig("Test Cup", registrationStart = now, registrationEnd = now.plusSeconds(60), startAt = now.plusSeconds(120))
        val t = TournamentDocument("test", config, "admin", createdAt = now, updatedAt = now)
        val generated = planner.generate(t, (1..16).map { "p$it" })
        assertThat(generated.matches.map { it.id }.distinct()).hasSize(15)
        assertThat(generated.matches.filter { it.roundNumber == 1 }.flatMap { it.sources }.distinct()).hasSize(16)
        assertThatThrownBy { planner.generate(generated, (1..16).map { "p$it" }) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
