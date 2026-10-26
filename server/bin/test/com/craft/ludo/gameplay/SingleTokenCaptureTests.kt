package com.craft.ludo.gameplay

import com.craft.ludo.gameplay.bot.CandidateMove
import com.craft.ludo.gameplay.bot.SuperiorBotEngine
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class SingleTokenCaptureTests {

    @Test
    fun `SuperiorBotEngine simulateMove only captures one opponent token when two are on the same cell`() {
        val redTargetKey = boardCellKey("red", 2, 0)
        val greenMatchingProgress = (0..MAIN_PATH_LAST_PROGRESS).first { progress ->
            boardCellKey("green", progress, 0) == redTargetKey
        }

        val players = listOf(
            MatchPlayerState(
                userId = "p1",
                displayName = "Player1",
                color = "red",
                isBot = true,
                tokens = listOf(0, -1, -1, -1),
            ),
            MatchPlayerState(
                userId = "p2",
                displayName = "Player2",
                color = "green",
                isBot = false,
                tokens = listOf(greenMatchingProgress, greenMatchingProgress, -1, -1),
            ),
        )

        val move = CandidateMove(tokenIndex = 0, fromProgress = 0, toProgress = 2)
        val resultingPlayers = SuperiorBotEngine.simulateMove(players, playerIndex = 0, move = move, diceValue = 2)

        val updatedGreenTokens = resultingPlayers[1].tokens
        val killedCount = updatedGreenTokens.count { it == -1 }
        val remainingCount = updatedGreenTokens.count { it == greenMatchingProgress }

        assertThat(killedCount).isEqualTo(3) // 2 original -1s plus 1 newly captured token
        assertThat(remainingCount).isEqualTo(1) // 1 token remains on the cell
    }

    @Test
    fun `applyTokenMove grants extra turn when token reaches final home cell`() {
        val p1 = MatchPlayerState(
            userId = "p1",
            displayName = "Player1",
            color = "red",
            isBot = false,
            // token 0 at 54 + dice 2 = 56 (FINISHED_PROGRESS)
            // token 1 at -1 so match is not won yet
            tokens = listOf(54, -1, -1, -1),
        )
        val p2 = MatchPlayerState(
            userId = "p2",
            displayName = "Player2",
            color = "yellow",
            isBot = false,
            tokens = listOf(-1, -1, -1, -1),
        )

        val match = MatchDocument(
            id = "test-match",
            roomId = "room-1",
            roomCode = "R1",
            mode = RoomMode.ONLINE_PUBLIC,
            turnTimeoutSeconds = 15,
            currentTurnUserId = "p1",
            currentTurnDisplayName = "Player1",
            createdAt = Instant.now(),
            status = MatchStatus.ACTIVE,
            phase = MatchPhase.AWAITING_MOVE,
            players = listOf(p1, p2),
            currentPlayerIndex = 0,
            dice = 2,
            selectableTokenIndexes = listOf(0),
            updatedAt = Instant.now(),
        )

        val nextMatch = testApplyMove(match, 0)

        // Reaching home with token 0 grants extra turn (pendingNextPlayerIndex remains 0)
        assertThat(nextMatch.pendingNextPlayerIndex).isEqualTo(0)
    }

    private fun testApplyMove(match: MatchDocument, tokenIndex: Int): MatchDocument {
        val activePlayer = match.players[match.currentPlayerIndex]
        val diceValue = match.dice ?: 1
        val mutablePlayers = match.players.map { player ->
            player.copy(tokens = player.tokens.toMutableList())
        }.toMutableList()
        val activeTokens = mutablePlayers[match.currentPlayerIndex].tokens.toMutableList()
        val currentProgress = activeTokens[tokenIndex]
        val nextProgress = if (currentProgress == -1) 0 else currentProgress + diceValue
        activeTokens[tokenIndex] = nextProgress
        mutablePlayers[match.currentPlayerIndex] = mutablePlayers[match.currentPlayerIndex].copy(tokens = activeTokens)

        val reachedHome = nextProgress == FINISHED_PROGRESS
        val hasWon = mutablePlayers[match.currentPlayerIndex].tokens.all { it == FINISHED_PROGRESS }
        val extraTurn = reachedHome

        val nextPlayerIndex = if (extraTurn) match.currentPlayerIndex else (match.currentPlayerIndex + 1) % match.players.size

        return match.copy(
            players = mutablePlayers,
            pendingNextPlayerIndex = if (hasWon) null else nextPlayerIndex,
        )
    }
}
