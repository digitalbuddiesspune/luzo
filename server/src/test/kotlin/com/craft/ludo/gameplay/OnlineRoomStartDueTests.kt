package com.craft.ludo.gameplay

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class OnlineRoomStartDueTests {
    @Test
    fun `starts waiting room when lobby deadline has passed`() {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val room = onlineWaitingRoom(
            ownedWaitingDeadlineAt = now.minusSeconds(1),
        )

        assertThat(shouldStartOnlineWaitingRoom(room, now)).isTrue()
        assertThat(canProcessOnlineWaitingRoom(room, now)).isTrue()
    }

    @Test
    fun `does not start waiting room before lobby deadline`() {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val room = onlineWaitingRoom(
            ownedWaitingDeadlineAt = now.plusSeconds(30),
        )

        assertThat(shouldStartOnlineWaitingRoom(room, now)).isFalse()
    }

    @Test
    fun `recovers stale starting room`() {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val room = onlineWaitingRoom(
            status = RoomStatus.STARTING,
            updatedAt = now.minusMillis(ONLINE_ROOM_STARTING_RECOVERY_MILLIS + 1),
        )

        assertThat(isOnlineRoomStartingStale(room, now)).isTrue()
        assertThat(shouldStartOnlineWaitingRoom(room, now)).isTrue()
    }

    @Test
    fun `does not recover fresh starting room`() {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val room = onlineWaitingRoom(
            status = RoomStatus.STARTING,
            updatedAt = now.minusSeconds(1),
        )

        assertThat(isOnlineRoomStartingStale(room, now)).isFalse()
        assertThat(shouldStartOnlineWaitingRoom(room, now)).isFalse()
    }

    private fun onlineWaitingRoom(
        status: RoomStatus = RoomStatus.WAITING,
        ownedWaitingDeadlineAt: Instant? = Instant.parse("2026-01-01T12:00:00Z"),
        updatedAt: Instant = Instant.parse("2026-01-01T12:00:00Z"),
    ): RoomDocument {
        return RoomDocument(
            mode = RoomMode.ONLINE_PUBLIC,
            status = status,
            maxPlayers = 2,
            entryFee = 100,
            ownerInstanceId = "instance-a",
            createdAt = Instant.parse("2026-01-01T11:59:00Z"),
            updatedAt = updatedAt,
            ownedWaitingDeadlineAt = ownedWaitingDeadlineAt,
            seats = listOf(
                RoomSeat(
                    userId = "player-1",
                    displayName = "Player 1",
                    color = "blue",
                    joinedAt = Instant.parse("2026-01-01T11:59:00Z"),
                ),
            ),
        )
    }
}
