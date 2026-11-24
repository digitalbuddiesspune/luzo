package com.craft.ludo.tournament

import com.craft.ludo.wallet.WalletReservation
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

object TournamentPolicy {
    const val PAID_ENTRY_ENABLED = true
    const val CASH_PRIZES_ENABLED = true
    const val MAX_CAPACITY = 512
}

enum class TournamentStatus { DRAFT, UPCOMING, REGISTRATION_OPEN, REGISTRATION_CLOSED, BRACKET_GENERATED, LIVE, COMPLETED, CANCELLED, FAILED, UNDER_REVIEW }
enum class TournamentMatchStatus { WAITING, READY, IN_PROGRESS, COMPLETED, NO_SHOW, CANCELLED, DISPUTED }
enum class EntryType { FREE, PAID }

data class TournamentConfig(
    val name: String,
    val description: String = "",
    val bannerUrl: String = "",
    val entryType: EntryType = EntryType.FREE,
    val gameFormat: Int = 2,
    val maxParticipants: Int = 16,
    val entryFee: Long = 0,
    val prizePoints: Long = 0,
    val registrationStart: Instant,
    val registrationEnd: Instant,
    val startAt: Instant,
    // Empty means automatically allocate qualifiers so the next round is playable.
    val qualifiersPerRound: List<Int> = emptyList(),
    val joinTimeoutSeconds: Long = 120,
    val matchDurationSeconds: Long = 3600,
    val reconnectSeconds: Long = 60,
    val rules: String = "No-shows forfeit their match. Byes advance without elimination. A timed-out match is reviewed by an administrator. Rewards are non-cash tournament points.",
)

@Document("tournament_registrations")
data class TournamentRegistration(
    @Id val id: String,
    val tournamentId: String,
    val userId: String,
    val displayName: String,
    val registeredAt: Instant,
    val status: String = "CONFIRMED",
    val paymentStatus: String = "NOT_REQUIRED",
    val entryFee: Long = 0,
    val walletReservation: WalletReservation? = null,
)

@Document("tournament_rounds")
data class TournamentRound(
    @Id val id: String,
    val tournamentId: String,
    val number: Int,
    val status: String = "WAITING",
    val matchIds: List<String>,
    val byeSources: List<String> = emptyList(),
    val qualifiedPlayerIds: List<String> = emptyList(),
)

@Document("tournament_matches")
data class TournamentMatch(
    @Id val id: String,
    val tournamentId: String,
    val roundNumber: Int,
    val matchNumber: Int,
    val gameFormat: Int,
    val sources: List<String>,
    val qualifierCount: Int,
    val assignedPlayerIds: List<String> = emptyList(),
    val joinedPlayerIds: List<String> = emptyList(),
    val noShowPlayerIds: List<String> = emptyList(),
    val gameSessionId: String? = null,
    val status: TournamentMatchStatus = TournamentMatchStatus.WAITING,
    val winnerId: String? = null,
    val finishingOrder: List<String> = emptyList(),
    val qualifiedPlayerIds: List<String> = emptyList(),
    val joinDeadline: Instant? = null,
    val startTime: Instant? = null,
    val completionTime: Instant? = null,
    val resultVerified: Boolean = false,
    val resultSequence: Long? = null,
    val attempt: Int = 1,
)

@Document("tournament_participants")
data class TournamentParticipant(
    @Id val id: String,
    val tournamentId: String,
    val userId: String,
    val displayName: String,
    val currentRound: Int = 0,
    val currentMatchId: String? = null,
    val status: String = "REGISTERED",
    val finalRank: Int? = null,
    val eliminationReason: String? = null,
)

@Document("tournament_rewards")
data class TournamentReward(
    @Id val id: String,
    val tournamentId: String,
    val userId: String,
    val rewardType: String = "POINTS",
    val amount: Long,
    val status: String = "SETTLED",
    val settledAt: Instant,
)

@Document("tournament_audit_logs")
data class TournamentAudit(
    @Id val id: String,
    val tournamentId: String,
    val actorId: String,
    val action: String,
    val reason: String,
    val createdAt: Instant,
)

@Document("tournaments")
data class TournamentDocument(
    @Id val id: String,
    val config: TournamentConfig,
    val adminId: String,
    val status: TournamentStatus = TournamentStatus.DRAFT,
    val registeredParticipants: Int = 0,
    val currentRound: Int = 0,
    val championId: String? = null,
    val registrations: List<TournamentRegistration> = emptyList(),
    val participants: List<TournamentParticipant> = emptyList(),
    val rounds: List<TournamentRound> = emptyList(),
    val matches: List<TournamentMatch> = emptyList(),
    val rewards: List<TournamentReward> = emptyList(),
    // Only the latest events are embedded; the audit collection retains all events.
    val events: List<TournamentAudit> = emptyList(),
    val createdAt: Instant,
    val updatedAt: Instant,
    @Version val version: Long? = null,
)

data class TournamentAdminAction(val reason: String, val userId: String? = null, val matchId: String? = null)
