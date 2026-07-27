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
    fun `starts when waiting room is full of real players`() {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val room = onlineWaitingRoom(
            ownedWaitingDeadlineAt = now.plusSeconds(30),
            maxPlayers = 2,
            seats = listOf(
                humanSeat("player-1", "blue"),
                humanSeat("player-2", "red"),
            ),
        )

        assertThat(isOnlineWaitingRoomFull(room)).isTrue()
        assertThat(shouldStartOnlineWaitingRoom(room, now)).isTrue()
    }

    @Test
    fun `marks waiting room with leftover bots as stuck and corrupt`() {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val room = onlineWaitingRoom(
            ownedWaitingDeadlineAt = now.plusSeconds(30),
            seats = listOf(
                humanSeat("player-1", "blue"),
                botSeat("bot-1", "red"),
            ),
        )

        assertThat(isCorruptOnlineWaitingRoom(room)).isTrue()
        assertThat(isStuckOnlineLobbyRoom(room, now)).isTrue()
        assertThat(canProcessOnlineWaitingRoom(room, now, "instance-b")).isTrue()
    }

    @Test
    fun `healthy waiting room without bots is not stuck`() {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val room = onlineWaitingRoom(
            ownedWaitingDeadlineAt = now.plusSeconds(30),
        )

        assertThat(isCorruptOnlineWaitingRoom(room)).isFalse()
        assertThat(isStuckOnlineLobbyRoom(room, now)).isFalse()
    }

    @Test
    fun `recovers stale starting room`() {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val room = onlineWaitingRoom(
            status = RoomStatus.STARTING,
            startAttemptId = "roomstart_stale",
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
    fun `recovers hung starting claim with no reservations after operator timeout window`() {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val room = onlineWaitingRoom(
            status = RoomStatus.STARTING,
            startAttemptId = "roomstart_hung",
            updatedAt = now.minusMillis(ONLINE_ROOM_STARTING_HUNG_CLAIM_MILLIS + 1),
        )

        assertThat(isOnlineRoomStartingHungClaim(room, now)).isTrue()
        assertThat(isOnlineRoomStartingStale(room, now)).isTrue()
        assertThat(shouldStartOnlineWaitingRoom(room, now)).isTrue()
        assertThat(isStuckOnlineLobbyRoom(room, now)).isTrue()
    }

    @Test
    fun `does not treat in-flight start as hung before operator debit timeout`() {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val room = onlineWaitingRoom(
            status = RoomStatus.STARTING,
            startAttemptId = "roomstart_inflight",
            // Operator debit timeout is 8s; hung window is 12s.
            updatedAt = now.minusMillis(8_000),
        )

        assertThat(isOnlineRoomStartingHungClaim(room, now)).isFalse()
        assertThat(isOnlineRoomStartingStale(room, now)).isFalse()
        assertThat(isStuckOnlineLobbyRoom(room, now)).isFalse()
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
        assertThat(isStuckOnlineLobbyRoom(room, now)).isTrue()
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

    private fun humanSeat(userId: String, color: String): RoomSeat =
        RoomSeat(
            userId = userId,
            displayName = userId,
            color = color,
            isBot = false,
            joinedAt = Instant.parse("2026-01-01T11:59:00Z"),
        )

    private fun botSeat(userId: String, color: String): RoomSeat =
        RoomSeat(
            userId = userId,
            displayName = "Bot",
            color = color,
            isBot = true,
            joinedAt = Instant.parse("2026-01-01T11:59:00Z"),
        )

    private fun onlineWaitingRoom(
        status: RoomStatus = RoomStatus.WAITING,
        ownedWaitingDeadlineAt: Instant? = Instant.parse("2026-01-01T12:00:00Z"),
        updatedAt: Instant = Instant.parse("2026-01-01T12:00:00Z"),
        startAttemptId: String? = null,
        ownerInstanceId: String = "instance-a",
        maxPlayers: Int = 2,
        seats: List<RoomSeat> = listOf(humanSeat("player-1", "blue")),
    ): RoomDocument {
        return RoomDocument(
            mode = RoomMode.ONLINE_PUBLIC,
            status = status,
            maxPlayers = maxPlayers,
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
