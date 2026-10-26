package com.craft.ludo.wallet

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WalletPayoutCalculationTests {
    @Test
    fun `one real and three bots charge fee on every seat`() {
        val winnerUserId = "019edbaa-d1926d76-7163-8684c7-73"
        val reservations = listOf(
            WalletReservation(
                userId = winnerUserId,
                transactionId = "roomfee_verify_user",
                amount = 100,
                externalDebitTransactionId = "roomfee_verify_user",
                externalDebitConfirmed = true,
                operatorToken = "operator-token",
                operatorUserId = "operator-user",
                operatorId = "operator",
            ),
            WalletReservation(
                userId = "bot_red",
                transactionId = "botfee_red",
                amount = 100,
                synthetic = true,
            ),
            WalletReservation(
                userId = "bot_yellow",
                transactionId = "botfee_yellow",
                amount = 100,
                synthetic = true,
            ),
            WalletReservation(
                userId = "bot_blue",
                transactionId = "botfee_blue",
                amount = 100,
                synthetic = true,
            ),
        )

        val payoutAmount = calculateWinnerLedgerPayoutAmount(
            winnerUserId = winnerUserId,
            paidReservations = reservations,
            platformFeePerPlayer = 10,
        )

        // 400 pot - (10 fee × 4 seats) = 360
        assertThat(calculateFlatPlatformFeeAmount(reservations, 10)).isEqualTo(40)
        assertThat(payoutAmount).isEqualTo(360)
    }

    @Test
    fun `one real and one bot charge fee on both seats so winner gets one hundred eighty`() {
        val winnerUserId = "player-a"
        val reservations = listOf(
            WalletReservation(
                userId = winnerUserId,
                transactionId = "roomfee_a",
                amount = 100,
            ),
            WalletReservation(
                userId = "bot_red",
                transactionId = "botfee_red",
                amount = 100,
                synthetic = true,
            ),
        )

        val payoutAmount = calculateWinnerLedgerPayoutAmount(
            winnerUserId = winnerUserId,
            paidReservations = reservations,
            platformFeePerPlayer = 10,
        )

        assertThat(calculateFlatPlatformFeeAmount(reservations, 10)).isEqualTo(20)
        assertThat(payoutAmount).isEqualTo(180)
    }

    @Test
    fun `two real players pay ten each so winner receives one hundred eighty`() {
        val winnerUserId = "player-a"
        val reservations = listOf(
            WalletReservation(
                userId = winnerUserId,
                transactionId = "roomfee_a",
                amount = 100,
            ),
            WalletReservation(
                userId = "player-b",
                transactionId = "roomfee_b",
                amount = 100,
            ),
        )

        val payoutAmount = calculateWinnerLedgerPayoutAmount(
            winnerUserId = winnerUserId,
            paidReservations = reservations,
            platformFeePerPlayer = 10,
        )

        assertThat(calculateFlatPlatformFeeAmount(reservations, 10)).isEqualTo(20)
        assertThat(payoutAmount).isEqualTo(180)
    }

    @Test
    fun `bot winner payout amount still reflects full pot minus seat fees`() {
        val humanUserId = "019edbaa-d1926d76-7163-8684c7-73"
        val botWinnerUserId = "bot_red"
        val reservations = listOf(
            WalletReservation(
                userId = humanUserId,
                transactionId = "roomfee_verify_user",
                amount = 100,
                externalDebitTransactionId = "roomfee_verify_user",
                externalDebitConfirmed = true,
                operatorToken = "operator-token",
                operatorUserId = "operator-user",
                operatorId = "operator",
            ),
            WalletReservation(
                userId = botWinnerUserId,
                transactionId = "botfee_red",
                amount = 100,
                synthetic = true,
            ),
            WalletReservation(
                userId = "bot_yellow",
                transactionId = "botfee_yellow",
                amount = 100,
                synthetic = true,
            ),
            WalletReservation(
                userId = "bot_blue",
                transactionId = "botfee_blue",
                amount = 100,
                synthetic = true,
            ),
        )

        val payoutAmount = calculateWinnerLedgerPayoutAmount(
            winnerUserId = botWinnerUserId,
            paidReservations = reservations,
            platformFeePerPlayer = 10,
        )

        assertThat(payoutAmount).isEqualTo(360)
        assertThat(botWinnerUserId).isNotEqualTo(humanUserId)
    }

    @Test
    fun `house win captures only real player entry fees`() {
        val reservations = listOf(
            WalletReservation(
                userId = "human-1",
                transactionId = "roomfee_human_1",
                amount = 100,
            ),
            WalletReservation(
                userId = "human-2",
                transactionId = "roomfee_human_2",
                amount = 100,
                externalDebitTransactionId = "roomfee_human_2",
                externalDebitConfirmed = true,
                operatorToken = "operator-token",
                operatorUserId = "operator-user",
                operatorId = "operator",
            ),
            WalletReservation(
                userId = "bot_red",
                transactionId = "botfee_red",
                amount = 100,
                synthetic = true,
            ),
            WalletReservation(
                userId = "bot_yellow",
                transactionId = "botfee_yellow",
                amount = 100,
                synthetic = true,
            ),
        )

        assertThat(calculateHouseWinAmount(reservations)).isEqualTo(200)
    }
}
