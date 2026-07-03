package com.craft.ludo.gameplay

import com.craft.ludo.identity.SessionPrincipal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class SameSourceProtectionTests {
    @Test
    fun `blocks second real player with same operator user id`() {
        val seat = realSeat(
            userId = "local-user-1",
            operatorUserId = "DEMO_TEST_2134",
            ipAddress = "10.0.0.10",
        )
        val principal = principal(
            userId = "local-user-2",
            operatorUserId = " demo_test_2134 ",
            ipAddress = "10.0.0.11",
        )

        assertThat(isSameSourceRealPlayerSeat(seat, principal)).isTrue()
        assertThat(sameSourceReasonForSeat(seat, principal)).isEqualTo("same_operator_user_id")
    }

    @Test
    fun `blocks second real player from same ip address`() {
        val seat = realSeat(
            userId = "local-user-1",
            operatorUserId = "operator-user-1",
            ipAddress = "203.0.113.25",
        )
        val principal = principal(
            userId = "local-user-2",
            operatorUserId = "operator-user-2",
            ipAddress = "203.0.113.25",
        )

        assertThat(isSameSourceRealPlayerSeat(seat, principal)).isTrue()
        assertThat(sameSourceReasonForSeat(seat, principal)).isEqualTo("same_ip")
    }

    @Test
    fun `blocks repeated local user id`() {
        val seat = realSeat(
            userId = "local-user-1",
            operatorUserId = "operator-user-1",
            ipAddress = "203.0.113.25",
        )
        val principal = principal(
            userId = "local-user-1",
            operatorUserId = "operator-user-2",
            ipAddress = "203.0.113.26",
        )

        assertThat(isSameSourceRealPlayerSeat(seat, principal)).isTrue()
        assertThat(sameSourceReasonForSeat(seat, principal)).isEqualTo("same_user_id")
    }

    @Test
    fun `allows unrelated real players to join same room`() {
        val seat = realSeat(
            userId = "local-user-1",
            operatorUserId = "operator-user-1",
            ipAddress = "203.0.113.25",
        )
        val principal = principal(
            userId = "local-user-2",
            operatorUserId = "operator-user-2",
            ipAddress = "203.0.113.26",
        )

        assertThat(isSameSourceRealPlayerSeat(seat, principal)).isFalse()
        assertThat(sameSourceReasonForSeat(seat, principal)).isEqualTo("unknown")
    }

    @Test
    fun `ignores bot and abandoned seats for same-source checks`() {
        val botSeat = realSeat(
            userId = "bot-red",
            operatorUserId = "operator-user-1",
            ipAddress = "203.0.113.25",
            isBot = true,
        )
        val abandonedSeat = realSeat(
            userId = "abandoned-user",
            operatorUserId = "operator-user-1",
            ipAddress = "203.0.113.25",
            isAbandoned = true,
        )
        val principal = principal(
            userId = "local-user-2",
            operatorUserId = "operator-user-1",
            ipAddress = "203.0.113.25",
        )

        assertThat(isSameSourceRealPlayerSeat(botSeat, principal)).isFalse()
        assertThat(isSameSourceRealPlayerSeat(abandonedSeat, principal)).isFalse()
    }

    @Test
    fun `detects same source among private room seats before start`() {
        val left = realSeat(
            userId = "local-user-1",
            operatorUserId = "operator-user-1",
            ipAddress = "203.0.113.25",
        )
        val right = realSeat(
            userId = "local-user-2",
            operatorUserId = "operator-user-2",
            ipAddress = "203.0.113.25",
        )

        assertThat(isSameSourceSeatPair(left, right)).isTrue()
        assertThat(sameSourceReasonForSeatPair(left, right)).isEqualTo("same_ip")
    }

    private fun realSeat(
        userId: String,
        operatorUserId: String?,
        ipAddress: String?,
        isBot: Boolean = false,
        isAbandoned: Boolean = false,
    ) = RoomSeat(
        userId = userId,
        displayName = userId,
        color = "red",
        isBot = isBot,
        isAbandoned = isAbandoned,
        joinedAt = Instant.parse("2026-06-30T00:00:00Z"),
        ipAddress = ipAddress,
        operatorUserId = operatorUserId,
        operatorId = "operator",
    )

    private fun principal(
        userId: String,
        operatorUserId: String?,
        ipAddress: String?,
    ) = SessionPrincipal(
        id = userId,
        sessionToken = "session-$userId",
        displayName = userId,
        ipAddress = ipAddress,
        operatorUserId = operatorUserId,
        operatorId = "operator",
    )
}
