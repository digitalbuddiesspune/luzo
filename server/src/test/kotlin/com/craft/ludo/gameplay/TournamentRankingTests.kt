package com.craft.ludo.gameplay

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class TournamentRankingTests {
    private val now = Instant.parse("2026-11-20T00:00:00Z")
    private fun match() = MatchDocument(roomId = "r", roomCode = "ROOM", mode = RoomMode.TOURNAMENT,
        status = MatchStatus.ACTIVE, phase = MatchPhase.AWAITING_MOVE, turnTimeoutSeconds = 60,
        currentPlayerIndex = 0, currentTurnUserId = "p0", currentTurnDisplayName = "P0",
        players = (0..3).map { MatchPlayerState("p$it", "P$it", listOf("red", "green", "yellow", "blue")[it], false, tokens = listOf(-1, -1, -1, -1)) },
        createdAt = now, updatedAt = now)

    @Test fun `four player tournament continues after first winner and records actual order`() {
        val first = recordTournamentFinish(match(), "p0", now, 750)
        assertThat(first.status).isEqualTo(MatchStatus.ACTIVE)
        assertThat(first.winnerUserId).isNull()
        val second = recordTournamentFinish(first, "p2", now, 750)
        assertThat(second.status).isEqualTo(MatchStatus.ACTIVE)
        val third = recordTournamentFinish(second, "p1", now, 750)
        assertThat(third.status).isEqualTo(MatchStatus.FINISHED)
        assertThat(third.tournamentFinishingOrder).containsExactly("p0", "p2", "p1", "p3")
        assertThat(third.winnerUserId).isEqualTo("p0")
    }
    @Test fun `forfeit retains identity and never qualifies the forfeiting player`() {
        val result = recordTournamentForfeit(match(), now, 750)
        assertThat(result.players.first().userId).isEqualTo("p0")
        assertThat(result.players.first().isAbandoned).isTrue()
        assertThat(result.tournamentFinishingOrder).doesNotContain("p0")
    }
}
