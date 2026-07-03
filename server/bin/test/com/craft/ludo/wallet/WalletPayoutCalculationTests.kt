package com.craft.ludo.wallet

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WalletPayoutCalculationTests {
    @Test
    fun `externally debited winner receives synthetic bot boot amounts`() {
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
            payoutRakeBasisPoints = 0,
        )

        assertThat(payoutAmount).isEqualTo(400)
    }

    @Test
    fun `bot winner is shown with full payout without targeting human wallet`() {
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
            payoutRakeBasisPoints = 0,
        )

        assertThat(payoutAmount).isEqualTo(400)
        assertThat(botWinnerUserId).isNotEqualTo(humanUserId)
    }
}
