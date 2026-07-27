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
        assertThat(canProcessOnlineWaitingRoom(room, now, "instance-a")).isTrue()
        assertThat(canProcessOnlineWaitingRoom(room, now, "instance-b")).isTrue()
    }

    @Test
    fun `does not start waiting room before lobby deadline`() {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val room = onlineWaitingRoom(
            ownedWaitingDeadlineAt = now.plusSeconds(30),
        )

        assertThat(shouldStartOnlineWaitingRoom(room, now)).isFalse()
        assertThat(canProcessOnlineWaitingRoom(room, now, "instance-a")).isFalse()
    }

    @Test
    fun `owner instance can process its own due room`() {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val room = onlineWaitingRoom(
            ownedWaitingDeadlineAt = now.minusSeconds(1),
            ownerInstanceId = "instance-a",
        )

        assertThat(canProcessOnlineWaitingRoom(room, now, "instance-a")).isTrue()
    }

    @Test
    fun `marks waiting room with leftover bots as stuck`() {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val room = onlineWaitingRoom(
            ownedWaitingDeadlineAt = now.plusSeconds(30),
            seats = listOf(
                RoomSeat(
                    userId = "player-1",
                    displayName = "Player 1",
                    color = "blue",
                    isBot = false,
                    joinedAt = Instant.parse("2026-01-01T11:59:00Z"),
                ),
                RoomSeat(
                    userId = "bot-1",
                    displayName = "Bot",
                    color = "red",
                    isBot = true,
                    joinedAt = Instant.parse("2026-01-01T11:59:00Z"),
                ),
            ),
        )

        assertThat(isCorruptOnlineWaitingRoom(room)).isTrue()
        assertThat(isStuckOnlineLobbyRoom(room, now)).isTrue()
        assertThat(canProcessOnlineWaitingRoom(room, now, "instance-b")).isTrue()
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
        assertThat(isStuckOnlineLobbyRoom(room, now)).isTrue()
    }

    @Test
    fun `does not recover fresh starting room`() {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val room = onlineWaitingRoom(
            status = RoomStatus.STARTING,
            startAttemptId = "roomstart_fresh",
            updatedAt = now.minusMillis(500),
        )

        assertThat(isOnlineRoomStartingStale(room, now)).isFalse()
        assertThat(shouldStartOnlineWaitingRoom(room, now)).isFalse()
        assertThat(isStuckOnlineLobbyRoom(room, now)).isFalse()
    }

    @Test
    fun `recovers hung starting claim with no reservations`() {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val room = onlineWaitingRoom(
            status = RoomStatus.STARTING,
            startAttemptId = "roomstart_hung",
            updatedAt = now.minusMillis(ONLINE_ROOM_STARTING_HUNG_CLAIM_MILLIS + 1),
        )

        assertThat(isOnlineRoomStartingHungClaim(room, now)).isTrue()
        assertThat(isOnlineRoomStartingStale(room, now)).isTrue()
        assertThat(shouldStartOnlineWaitingRoom(room, now)).isTrue()
    }

    @Test
    fun `recovers corrupt starting room without start attempt id`() {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val room = onlineWaitingRoom(
            status = RoomStatus.STARTING,
            startAttemptId = null,
            updatedAt = now.minusSeconds(1),
        )

        assertThat(isOnlineRoomStartingCorrupt(room)).isTrue()
        assertThat(isOnlineRoomStartingStale(room, now)).isTrue()
        assertThat(shouldStartOnlineWaitingRoom(room, now)).isTrue()
    }

    private fun onlineWaitingRoom(
        status: RoomStatus = RoomStatus.WAITING,
        ownedWaitingDeadlineAt: Instant? = Instant.parse("2026-01-01T12:00:00Z"),
        updatedAt: Instant = Instant.parse("2026-01-01T12:00:00Z"),
        startAttemptId: String? = null,
        ownerInstanceId: String = "instance-a",
        seats: List<RoomSeat> = listOf(
            RoomSeat(
                userId = "player-1",
                displayName = "Player 1",
                color = "blue",
                isBot = false,
                joinedAt = Instant.parse("2026-01-01T11:59:00Z"),
            ),
        ),
    ): RoomDocument {
        return RoomDocument(
            mode = RoomMode.ONLINE_PUBLIC,
            status = status,
            maxPlayers = 2,
            entryFee = 100,
            ownerInstanceId = ownerInstanceId,
            createdAt = Instant.parse("2026-01-01T11:59:00Z"),
            updatedAt = updatedAt,
            ownedWaitingDeadlineAt = ownedWaitingDeadlineAt,
            startAttemptId = startAttemptId,
            seats = seats,
        )
    }
}
