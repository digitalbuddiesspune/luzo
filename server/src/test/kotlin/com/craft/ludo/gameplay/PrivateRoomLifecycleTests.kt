package com.craft.ludo.gameplay

import com.craft.ludo.shared.api.DomainException
import com.craft.ludo.wallet.WalletReservation
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class PrivateRoomLifecycleTests {
    private val now = Instant.parse("2026-10-26T10:00:00Z")
    private fun seat(id: String, abandoned: Boolean = false, bot: Boolean = false) = RoomSeat(
        userId = id, displayName = id, color = "red", isBot = bot,
        isAbandoned = abandoned, joinedAt = now,
    )
    private fun room(mode: RoomMode = RoomMode.PRIVATE_FRIENDS) = RoomDocument(
        id = "friends-room", code = "ABC123", mode = mode, status = RoomStatus.ACTIVE,
        maxPlayers = 2, roomName = "Weekend Table", hostUserId = "host", entryFee = 100,
        createdAt = now, updatedAt = now, seats = listOf(seat("host"), seat("friend")),
        matchId = "round-one", providerRoundId = "round-one", startAttemptId = "attempt-one",
        walletReservations = listOf(WalletReservation("host", "debit-one", 100)),
        waitingDeadlineAt = now, ownedWaitingDeadlineAt = now,
    )

    @Test
    fun `accepts exactly two or four players with a backwards compatible default`() {
        assertThat(normalizePrivateMaxPlayers(null)).isEqualTo(4)
        assertThat(normalizePrivateMaxPlayers(2)).isEqualTo(2)
        assertThat(normalizePrivateMaxPlayers(4)).isEqualTo(4)
        listOf(-1, 0, 1, 3, 5, 100).forEach { count ->
            assertThatThrownBy { normalizePrivateMaxPlayers(count) }.isInstanceOf(DomainException::class.java)
        }
    }

    @Test
    fun `friends keep the same room code host and seats for another round`() {
        val original = room()
        val lobby = original.afterFinishedRound(now.plusSeconds(60))
        assertThat(lobby.status).isEqualTo(RoomStatus.WAITING)
        assertThat(lobby.id).isEqualTo(original.id)
        assertThat(lobby.code).isEqualTo(original.code)
        assertThat(lobby.roomName).isEqualTo(original.roomName)
        assertThat(lobby.maxPlayers).isEqualTo(2)
        assertThat(lobby.entryFee).isEqualTo(100)
        assertThat(lobby.hostUserId).isEqualTo("host")
        assertThat(lobby.seats.map { it.userId }).containsExactly("host", "friend")
        assertThat(lobby.matchId).isNull()
        assertThat(lobby.walletReservations).isEmpty()
        assertThat(lobby.providerRoundId).isNull()
        assertThat(lobby.startAttemptId).isNull()
        assertThat(lobby.waitingDeadlineAt).isNull()
        assertThat(lobby.ownedWaitingDeadlineAt).isNull()
    }

    @Test
    fun `a second completed round cannot reuse previous match or entry fee reservations`() {
        val firstLobby = room().afterFinishedRound(now)
        val secondLobby = firstLobby.copy(
            status = RoomStatus.ACTIVE, matchId = "round-two", providerRoundId = "round-two",
            walletReservations = listOf(WalletReservation("friend", "debit-two", 100)),
        ).afterFinishedRound(now.plusSeconds(120))
        assertThat(secondLobby.code).isEqualTo("ABC123")
        assertThat(secondLobby.seats.map { it.userId }).containsExactly("host", "friend")
        assertThat(secondLobby.walletReservations).isEmpty()
        assertThat(secondLobby.matchId).isNull()
        assertThat(secondLobby.providerRoundId).isNull()
    }

    @Test
    fun `departed host transfers ownership to a remaining friend without reviving abandoned players`() {
        val lobby = room().copy(seats = listOf(
            seat("host", abandoned = true), seat("friend"), seat("bot", bot = true),
        )).afterFinishedRound(now)
        assertThat(lobby.hostUserId).isEqualTo("friend")
        assertThat(lobby.seats.map { it.userId }).containsExactly("friend")
        assertThat(lobby.status).isEqualTo(RoomStatus.WAITING)
    }

    @Test
    fun `empty private room closes rather than opening a lobby without a host`() {
        val closed = room().copy(seats = listOf(seat("host", abandoned = true))).afterFinishedRound(now)
        assertThat(closed.status).isEqualTo(RoomStatus.FINISHED)
        assertThat(closed.hostUserId).isNull()
        assertThat(closed.seats).isEmpty()
    }

    @Test
    fun `public matches still finish without becoming reusable friend lobbies`() {
        val original = room(RoomMode.ONLINE_PUBLIC)
        val finished = original.afterFinishedRound(now)
        assertThat(finished.status).isEqualTo(RoomStatus.FINISHED)
        assertThat(finished.matchId).isEqualTo(original.matchId)
        assertThat(finished.walletReservations).isEqualTo(original.walletReservations)
    }
}
