package com.craft.ludo.gameplay

import com.craft.ludo.identity.SessionPrincipal
import com.craft.ludo.identity.SessionPrincipalResolver
import com.craft.ludo.shared.api.DomainException
import com.craft.ludo.shared.config.AppProperties
import com.craft.ludo.shared.support.newId
import com.craft.ludo.gameplay.bot.BotDiceDecision
import com.craft.ludo.gameplay.bot.BotDiceSettings
import com.craft.ludo.gameplay.bot.KillStalkPlan
import com.craft.ludo.gameplay.bot.buildDiceRollContext
import com.craft.ludo.gameplay.bot.incrementPlayerRollStats
import com.craft.ludo.gameplay.bot.rollBotDice
import com.craft.ludo.gameplay.bot.rollUserDice
import com.craft.ludo.gameplay.bot.stalkPlanFor
import com.craft.ludo.gameplay.bot.upsertStalkPlan
import com.craft.ludo.wallet.WalletReservation
import com.craft.ludo.wallet.WalletService
import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.mongodb.core.mapping.Field
import org.springframework.data.domain.Sort
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.data.mongodb.repository.ReactiveMongoRepository
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketSession
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import reactor.util.retry.Retry

enum class RoomMode {
    ONLINE_PUBLIC,
    PRIVATE_FRIENDS,
}

enum class RoomStatus {
    WAITING,
    STARTING,
    ACTIVE,
    FINISHED,
}

enum class MatchStatus {
    ACTIVE,
    FINISHED,
}

enum class MatchPhase {
    ROLLING,
    AWAITING_MOVE,
    BOT_MOVING,
    ADVANCING,
    FINISHED,
}

data class BoardCell(
    val row: Int,
    val col: Int,
)

data class RoomSeat(
    val userId: String,
    val displayName: String,
    val color: String,
    val isBot: Boolean,
    val isAbandoned: Boolean = false,
    val joinedAt: Instant,
    val ipAddress: String? = null,
    val operatorUserId: String? = null,
    val operatorId: String? = null,
)

@Document("rooms")
data class RoomDocument(
    @Id
    val id: String = newId("room"),
    val code: String = newId("code").takeLast(6).uppercase(),
    val mode: RoomMode,
    val status: RoomStatus,
    val maxPlayers: Int,
    val entryFee: Long = 100,
    val roomName: String? = null,
    val hostUserId: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val waitingDeadlineAt: Instant? = null,
    val ownedWaitingDeadlineAt: Instant? = null,
    val startAttemptId: String? = null,
    val ownerInstanceId: String? = null,
    val seats: List<RoomSeat>,
    val walletReservations: List<WalletReservation> = emptyList(),
    val matchId: String? = null,
    @Version
    val version: Long? = null,
)

data class MatchPlayerState(
    val userId: String,
    val displayName: String,
    val color: String,
    @get:JsonProperty("isBot")
    val isBot: Boolean,
    @get:JsonProperty("isAbandoned")
    val isAbandoned: Boolean = false,
    val tokens: List<Int>,
    /**
     * Board positions at the moment this seat left or was kicked. Kept when
     * [tokens] are cleared so admin P&L can still show where pieces were.
     */
    val finalTokens: List<Int>? = null,
    // Total missed turns in this match (not consecutive). Kept under the legacy
    // Mongo field name so in-progress matches keep their counters.
    @Field("consecutiveMissedTurns")
    @get:JsonProperty("missedTurns")
    @get:JsonAlias("consecutiveMissedTurns")
    val missedTurns: Int = 0,
    val matchDiceRollCount: Int = 0,
    val matchSixCount: Int = 0,
    val operatorUserId: String? = null,
    val operatorId: String? = null,
)

data class MatchEvent(
    val id: String = newId("evt"),
    val actor: String,
    val detail: String,
    val createdAt: Instant,
)

@Document("matches")
data class MatchDocument(
    @Id
    val id: String = newId("match"),
    val roomId: String,
    val roomCode: String,
    val mode: RoomMode,
    val status: MatchStatus,
    val phase: MatchPhase,
    val entryFee: Long = 100,
    val potAmount: Long = 0,
    val turnTimeoutSeconds: Long,
    val currentPlayerIndex: Int,
    val currentTurnUserId: String,
    val currentTurnDisplayName: String,
    val consecutiveSixCount: Int = 0,
    val lastRollUserId: String? = null,
    val lastRollDisplayName: String? = null,
    val lastRollDice: Int? = null,
    val dice: Int? = null,
    val players: List<MatchPlayerState>,
    val selectableTokenIndexes: List<Int> = emptyList(),
    val pendingNextPlayerIndex: Int? = null,
    /** Server-only bot hunt state; never exposed in match snapshots. */
    val botStalkPlans: List<KillStalkPlan> = emptyList(),
    val botForcedTokenIndex: Int? = null,
    val phaseDeadlineAt: Instant? = null,
    val turnDeadlineAt: Instant? = null,
    val winnerUserId: String? = null,
    val winnerDisplayName: String? = null,
    /** How the match ended: HOME, FORFEIT, FORFEIT_MISSED_TURNS, ABANDON_BOT, or HOUSE. */
    val winnerReason: String? = null,
    val sequence: Long = 1,
    val events: List<MatchEvent> = emptyList(),
    val createdAt: Instant,
    val updatedAt: Instant,
    @Version
    val version: Long? = null,
)

interface RoomRepository : ReactiveMongoRepository<RoomDocument, String> {
    fun findAllByStatusOrderByCreatedAtAsc(status: RoomStatus): Flux<RoomDocument>
}

interface MatchRepository : ReactiveMongoRepository<MatchDocument, String> {
    fun findAllByStatus(status: MatchStatus): Flux<MatchDocument>
    fun findByRoomId(roomId: String): Mono<MatchDocument>
}

data class RoomSummaryResponse(
    val roomId: String,
    val roomCode: String,
    val mode: RoomMode,
    val status: RoomStatus,
    val maxPlayers: Int,
    val entryFee: Long,
    val livePot: Long,
    val realPlayerCount: Int,
    val occupiedSeats: Int,
    val hasBots: Boolean,
    val waitingDeadlineAt: Instant?,
    val matchId: String?,
)

data class MatchSnapshotResponse(
    val matchId: String,
    val roomId: String,
    val roomCode: String,
    val mode: RoomMode,
    val status: MatchStatus,
    val phase: MatchPhase,
    val entryFee: Long,
    val potAmount: Long,
    val turnTimeoutSeconds: Long,
    val currentPlayerIndex: Int,
    val currentTurnUserId: String,
    val currentTurnDisplayName: String,
    val lastRollUserId: String?,
    val lastRollDisplayName: String?,
    val lastRollDice: Int?,
    val dice: Int?,
    val players: List<MatchPlayerState>,
    val selectableTokenIndexes: List<Int>,
    val pendingNextPlayerIndex: Int?,
    val turnDeadlineAt: Instant?,
    val winnerUserId: String?,
    val winnerDisplayName: String?,
    val winnerReason: String? = null,
    val sequence: Long,
    val events: List<MatchEvent>,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
)

data class JoinOnlineMatchResponse(
    val room: RoomSummaryResponse,
    val match: MatchSnapshotResponse? = null,
    val websocketPath: String? = null,
)

data class PrivateRoomMemberResponse(
    val userId: String,
    val displayName: String,
    val color: String,
    val isHost: Boolean,
    val joinedAt: Instant,
)

data class PrivateRoomStateResponse(
    val roomId: String,
    val roomCode: String,
    val roomName: String,
    val status: RoomStatus,
    val entryFee: Long,
    val maxPlayers: Int,
    val occupiedSeats: Int,
    val hostUserId: String?,
    val hostDisplayName: String?,
    val members: List<PrivateRoomMemberResponse>,
    val match: MatchSnapshotResponse? = null,
    val websocketPath: String? = null,
)

data class CreatePrivateRoomRequest(
    val roomName: String? = null,
    val displayName: String? = null,
    val entryFee: Long? = null,
)

data class JoinPrivateRoomRequest(
    val roomCode: String,
    val displayName: String? = null,
)

data class JoinOnlineMatchRequest(
    val maxPlayers: Int? = null,
)

data class TransferPrivateRoomHostRequest(
    val targetUserId: String,
)

data class MoveTokenRequest(
    val tokenIndex: Int,
)

data class IceServerResponse(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

data class WebRtcConfigResponse(
    val iceServers: List<IceServerResponse>,
)

data class WebRtcSignalRequest(
    val matchId: String,
    val targetUserId: String,
    val type: String,
    val sdp: String? = null,
    val candidate: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
)

data class WebRtcSignalEvent(
    val matchId: String,
    val senderUserId: String,
    val senderDisplayName: String,
    val targetUserId: String,
    val type: String,
    val sdp: String? = null,
    val candidate: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
    val createdAt: Instant,
)

data class MatchRealtimeEnvelope(
    val type: String,
    val match: MatchSnapshotResponse? = null,
    val signal: WebRtcSignalEvent? = null,
)

data class MatchRealtimeRedisMessage(
    val matchId: String,
    val payload: String,
    val originId: String? = null,
)

internal val playerColors = listOf("red", "green", "yellow", "blue")
private val clockwiseTurnColors = listOf("red", "green", "yellow", "blue")
private val diagonalColorPairs = listOf(
    listOf("red", "yellow"),
    listOf("green", "blue"),
)
private val indianBotUsernames: List<String> by lazy {
    RoomDocument::class.java.classLoader
        ?.getResourceAsStream("bot-usernames.txt")
        ?.bufferedReader()
        ?.useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }
        .orEmpty()
}

internal fun botDisplayName(
    usedNames: Collection<String> = emptyList(),
): String {
    val excluded = usedNames
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()
    val pool = indianBotUsernames.filter { name ->
        name.lowercase() !in excluded
    }
    val source = if (pool.isNotEmpty()) pool else indianBotUsernames
    if (source.isEmpty()) {
        return "Guest Player"
    }
    return source.random()
}

internal fun allowsPublicPvpMatchmaking(
    waitingRealPlayerCount: Int,
    joiningRealPlayerCount: Int = 1,
    threshold: Int = 0,
): Boolean = waitingRealPlayerCount + joiningRealPlayerCount > threshold

// Must exceed operator debit timeout (8s) so an in-flight start is not finished early.
internal const val ONLINE_ROOM_STARTING_RECOVERY_MILLIS = 15_000L
internal const val ONLINE_ROOM_STARTING_HUNG_CLAIM_MILLIS = 12_000L

internal fun isOnlineRoomStartingCorrupt(room: RoomDocument): Boolean {
    return room.status == RoomStatus.STARTING &&
        room.matchId == null &&
        room.startAttemptId == null
}

internal fun isOnlineRoomStartingHungClaim(room: RoomDocument, now: Instant): Boolean {
    if (room.status != RoomStatus.STARTING || room.matchId != null) {
        return false
    }
    // Claimed but never reserved entry fees — typically a hung/failed wallet debit.
    val hasRealReservation = room.walletReservations.any { reservation -> !reservation.synthetic }
    return !hasRealReservation &&
        room.updatedAt.plusMillis(ONLINE_ROOM_STARTING_HUNG_CLAIM_MILLIS).isBefore(now)
}

internal fun isOnlineRoomStartingStale(room: RoomDocument, now: Instant): Boolean {
    return isOnlineRoomStartingCorrupt(room) ||
        isOnlineRoomStartingHungClaim(room, now) ||
        (
            room.status == RoomStatus.STARTING &&
                room.matchId == null &&
                room.updatedAt.plusMillis(ONLINE_ROOM_STARTING_RECOVERY_MILLIS).isBefore(now)
            )
}

internal fun isOnlineRoomWaitingDeadlineDue(room: RoomDocument, now: Instant): Boolean {
    // Null deadline means the room must start ASAP (leave-from-STARTING / recovery).
    val deadline = room.effectiveWaitingDeadlineAt() ?: return true
    return !now.isBefore(deadline)
}

internal fun isOnlineWaitingRoomFull(room: RoomDocument): Boolean {
    return room.status == RoomStatus.WAITING &&
        room.mode == RoomMode.ONLINE_PUBLIC &&
        room.matchId == null &&
        countRealSeats(room) >= room.maxPlayers
}

internal fun shouldStartOnlineWaitingRoom(room: RoomDocument, now: Instant): Boolean {
    if (room.mode != RoomMode.ONLINE_PUBLIC || room.matchId != null) {
        return false
    }

    return when (room.status) {
        RoomStatus.WAITING -> isOnlineRoomWaitingDeadlineDue(room, now) || isOnlineWaitingRoomFull(room)
        RoomStatus.STARTING -> isOnlineRoomStartingStale(room, now)
        else -> false
    }
}

internal fun isCorruptOnlineWaitingRoom(room: RoomDocument): Boolean {
    return room.mode == RoomMode.ONLINE_PUBLIC &&
        room.matchId == null &&
        room.status == RoomStatus.WAITING &&
        room.seats.any { seat -> seat.isBot }
}

/**
 * Lobby rooms that cannot safely continue (failed start leftovers / hung claims).
 * Join should abandon these and seat the player in a fresh room.
 */
internal fun isStuckOnlineLobbyRoom(room: RoomDocument, now: Instant): Boolean {
    if (room.mode != RoomMode.ONLINE_PUBLIC || room.matchId != null) {
        return false
    }

    return when (room.status) {
        RoomStatus.WAITING -> isCorruptOnlineWaitingRoom(room)
        RoomStatus.STARTING -> isOnlineRoomStartingStale(room, now)
        else -> false
    }
}

internal fun canProcessOnlineWaitingRoom(
    room: RoomDocument,
    now: Instant,
    currentInstanceId: String,
): Boolean {
    if (!shouldStartOnlineWaitingRoom(room, now) && !isCorruptOnlineWaitingRoom(room)) {
        return false
    }

    val ownerId = room.ownerInstanceId
    // Only the owning instance ticks its rooms. Other instances may take over only when
    // the lobby is due/corrupt/stale — prevents a broken peer from rewriting healthy rooms.
    return ownerId.isNullOrBlank() ||
        ownerId == currentInstanceId ||
        isOnlineRoomWaitingDeadlineDue(room, now) ||
        isCorruptOnlineWaitingRoom(room) ||
        isOnlineRoomStartingStale(room, now)
}

private val boardPath = listOf(
    BoardCell(6, 1),
    BoardCell(6, 2),
    BoardCell(6, 3),
    BoardCell(6, 4),
    BoardCell(6, 5),
    BoardCell(5, 6),
    BoardCell(4, 6),
    BoardCell(3, 6),
    BoardCell(2, 6),
    BoardCell(1, 6),
    BoardCell(0, 6),
    BoardCell(0, 7),
    BoardCell(0, 8),
    BoardCell(1, 8),
    BoardCell(2, 8),
    BoardCell(3, 8),
    BoardCell(4, 8),
    BoardCell(5, 8),
    BoardCell(6, 9),
    BoardCell(6, 10),
    BoardCell(6, 11),
    BoardCell(6, 12),
    BoardCell(6, 13),
    BoardCell(6, 14),
    BoardCell(7, 14),
    BoardCell(8, 14),
    BoardCell(8, 13),
    BoardCell(8, 12),
    BoardCell(8, 11),
    BoardCell(8, 10),
    BoardCell(8, 9),
    BoardCell(9, 8),
    BoardCell(10, 8),
    BoardCell(11, 8),
    BoardCell(12, 8),
    BoardCell(13, 8),
    BoardCell(14, 8),
    BoardCell(14, 7),
    BoardCell(14, 6),
    BoardCell(13, 6),
    BoardCell(12, 6),
    BoardCell(11, 6),
    BoardCell(10, 6),
    BoardCell(9, 6),
    BoardCell(8, 5),
    BoardCell(8, 4),
    BoardCell(8, 3),
    BoardCell(8, 2),
    BoardCell(8, 1),
    BoardCell(8, 0),
    BoardCell(7, 0),
    BoardCell(6, 0),
)

private val startOffsets = mapOf(
    "red" to 0,
    "green" to 13,
    "yellow" to 26,
    "blue" to 39,
)

private val yardPositions = mapOf(
    "red" to listOf(BoardCell(1, 1), BoardCell(1, 4), BoardCell(4, 1), BoardCell(4, 4)),
    "green" to listOf(BoardCell(1, 10), BoardCell(1, 13), BoardCell(4, 10), BoardCell(4, 13)),
    "yellow" to listOf(BoardCell(10, 10), BoardCell(10, 13), BoardCell(13, 10), BoardCell(13, 13)),
    "blue" to listOf(BoardCell(10, 1), BoardCell(10, 4), BoardCell(13, 1), BoardCell(13, 4)),
)

private val homeLanes = mapOf(
    "red" to listOf(BoardCell(7, 1), BoardCell(7, 2), BoardCell(7, 3), BoardCell(7, 4), BoardCell(7, 5)),
    "green" to listOf(BoardCell(1, 7), BoardCell(2, 7), BoardCell(3, 7), BoardCell(4, 7), BoardCell(5, 7)),
    "yellow" to listOf(BoardCell(7, 13), BoardCell(7, 12), BoardCell(7, 11), BoardCell(7, 10), BoardCell(7, 9)),
    "blue" to listOf(BoardCell(13, 7), BoardCell(12, 7), BoardCell(11, 7), BoardCell(10, 7), BoardCell(9, 7)),
)

private val safeCellKeys = setOf(
    "6-1",
    "1-8",
    "8-13",
    "13-6",
    "2-6",
    "6-12",
    "12-8",
    "8-2",
)

internal const val MAIN_PATH_LAST_PROGRESS = 50
internal const val HOME_LANE_START_PROGRESS = 51
internal const val HOME_LANE_LAST_PROGRESS = 55
internal const val FINISHED_PROGRESS = 56

internal val ludoSafeCellKeys: Set<String> = safeCellKeys

internal fun countRealSeats(room: RoomDocument): Int = room.seats.count { !it.isBot && !it.isAbandoned }

private fun normalizeRequestedDisplayName(displayName: String?, fallback: String): String {
    val resolved = displayName?.trim().takeUnless { it.isNullOrBlank() } ?: fallback.trim()

    if (resolved.isBlank()) {
        throw DomainException(HttpStatus.BAD_REQUEST, "Display name cannot be blank.")
    }

    if (resolved.length > 24) {
        throw DomainException(HttpStatus.BAD_REQUEST, "Display name must be 24 characters or fewer.")
    }

    return resolved
}

private fun normalizeRequestedRoomName(roomName: String?): String {
    val resolved = roomName?.trim().takeUnless { it.isNullOrBlank() } ?: "Private Table"

    if (resolved.length > 32) {
        throw DomainException(HttpStatus.BAD_REQUEST, "Room name must be 32 characters or fewer.")
    }

    return resolved
}

private fun normalizeRequestedEntryFee(entryFee: Long?, fallback: Long): Long {
    val resolved = entryFee ?: fallback

    if (resolved <= 0) {
        throw DomainException(HttpStatus.BAD_REQUEST, "Entry fee must be greater than zero.")
    }

    return resolved
}

internal fun normalizeOnlineMaxPlayers(maxPlayers: Int?): Int {
    val resolved = maxPlayers ?: 4

    if (resolved != 2 && resolved != 4) {
        throw DomainException(HttpStatus.BAD_REQUEST, "Online matches support only 2 or 4 players.")
    }

    return resolved
}

internal fun normalizeSeatColors(seats: List<RoomSeat>): List<RoomSeat> {
    if (seats.isEmpty()) {
        return seats
    }

    val normalizedSeats = seats.toMutableList()
    val availableColors = playerColors.toMutableList()
    val unassignedSeatIndexes = mutableListOf<Int>()

    seats.forEachIndexed { index, seat ->
        if (!availableColors.remove(seat.color)) {
            unassignedSeatIndexes += index
        }
    }

    val fallbackColors = if (seats.size == 2 && unassignedSeatIndexes.size == seats.size) {
        // Fresh 2-seat rooms should land on diagonal corners immediately.
        assignSeatColors(2).toMutableList()
    } else if (
        seats.size == 2 &&
        unassignedSeatIndexes.size == 1 &&
        availableColors.isNotEmpty()
    ) {
        val occupiedColor = seats.firstOrNull { seat -> seat.color in playerColors }?.color
        val preferred = occupiedColor?.let(::diagonalOppositeColor)
        if (preferred != null && preferred in availableColors) {
            mutableListOf(preferred)
        } else {
            availableColors.shuffled().toMutableList()
        }
    } else {
        availableColors.shuffled().toMutableList()
    }

    unassignedSeatIndexes.forEachIndexed { colorIndex, seatIndex ->
        normalizedSeats[seatIndex] = seats[seatIndex].copy(color = fallbackColors[colorIndex])
    }

    return normalizedSeats
}

internal fun assignSeatColors(seatCount: Int): List<String> {
    require(seatCount in 1..playerColors.size) {
        "Seat count must be between 1 and ${playerColors.size}."
    }

    if (seatCount == 2) {
        return diagonalColorPairs.random().shuffled()
    }

    return playerColors.shuffled().take(seatCount)
}

internal fun diagonalOppositeColor(color: String): String {
    return when (color) {
        "red" -> "yellow"
        "yellow" -> "red"
        "green" -> "blue"
        "blue" -> "green"
        else -> playerColors.first { candidate -> candidate != color }
    }
}

internal fun randomizeSeatColors(seats: List<RoomSeat>): List<RoomSeat> {
    val assignedColors = assignSeatColors(seats.size)
    return seats.mapIndexed { index, seat ->
        seat.copy(color = assignedColors[index])
    }
}

private fun clockwiseColorIndex(color: String): Int {
    val index = clockwiseTurnColors.indexOf(color)
    return if (index == -1) clockwiseTurnColors.size else index
}

internal fun RoomDocument.toSummary(): RoomSummaryResponse {
    return RoomSummaryResponse(
        roomId = id,
        roomCode = code,
        mode = mode,
        status = status,
        maxPlayers = maxPlayers,
        entryFee = entryFee,
        livePot = walletReservations.sumOf { it.amount },
        realPlayerCount = countRealSeats(this),
        occupiedSeats = seats.size,
        hasBots = seats.any { it.isBot },
        waitingDeadlineAt = effectiveWaitingDeadlineAt(),
        matchId = matchId,
    )
}

internal fun RoomDocument.effectiveWaitingDeadlineAt(): Instant? {
    return ownedWaitingDeadlineAt ?: waitingDeadlineAt
}

private fun RoomDocument.toPrivateState(
    match: MatchDocument? = null,
    websocketPath: String? = null,
): PrivateRoomStateResponse {
    val hostSeat = seats.firstOrNull { seat -> seat.userId == hostUserId }

    return PrivateRoomStateResponse(
        roomId = id,
        roomCode = code,
        roomName = roomName ?: "Private Table",
        status = status,
        entryFee = entryFee,
        maxPlayers = maxPlayers,
        occupiedSeats = seats.count { !it.isBot && !it.isAbandoned },
        hostUserId = hostUserId,
        hostDisplayName = hostSeat?.displayName,
        members = seats
            .filter { !it.isBot && !it.isAbandoned }
            .map { seat ->
                PrivateRoomMemberResponse(
                    userId = seat.userId,
                    displayName = seat.displayName,
                    color = seat.color,
                    isHost = seat.userId == hostUserId,
                    joinedAt = seat.joinedAt,
                )
            },
        match = match?.toSnapshot(),
        websocketPath = websocketPath,
    )
}

internal fun MatchDocument.toSnapshot(): MatchSnapshotResponse {
    return MatchSnapshotResponse(
        matchId = id,
        roomId = roomId,
        roomCode = roomCode,
        mode = mode,
        status = status,
        phase = phase,
        entryFee = entryFee,
        potAmount = potAmount,
        turnTimeoutSeconds = turnTimeoutSeconds,
        currentPlayerIndex = currentPlayerIndex,
        currentTurnUserId = currentTurnUserId,
        currentTurnDisplayName = currentTurnDisplayName,
        lastRollUserId = lastRollUserId,
        lastRollDisplayName = lastRollDisplayName,
        lastRollDice = lastRollDice,
        dice = dice,
        players = players.map { player -> player.normalizedAbandonedState() },
        selectableTokenIndexes = selectableTokenIndexes,
        pendingNextPlayerIndex = pendingNextPlayerIndex,
        turnDeadlineAt = turnDeadlineAt,
        winnerUserId = winnerUserId,
        winnerDisplayName = winnerDisplayName,
        winnerReason = winnerReason,
        sequence = sequence,
        events = events,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

internal object WinnerReason {
    const val HOME = "HOME"
    const val FORFEIT = "FORFEIT"
    /** Opponent auto-removed after missing too many turns (AFK / timeout). */
    const val FORFEIT_MISSED_TURNS = "FORFEIT_MISSED_TURNS"
    const val ABANDON_BOT = "ABANDON_BOT"
    const val HOUSE = "HOUSE"
}

internal fun MatchPlayerState.isEffectivelyAbandoned(): Boolean {
    return isAbandoned || (!isBot && (userId.startsWith("abandoned_") || tokens.isEmpty()))
}

internal fun MatchPlayerState.normalizedAbandonedState(): MatchPlayerState {
    return if (isEffectivelyAbandoned() && !isAbandoned) {
        copy(isAbandoned = true)
    } else {
        this
    }
}

/** Clear live tokens but keep [finalTokens] for admin board review. */
internal fun MatchPlayerState.asAbandoned(abandonedUserId: String): MatchPlayerState {
    return copy(
        userId = abandonedUserId,
        isAbandoned = true,
        finalTokens = finalTokens ?: tokens.takeIf { it.isNotEmpty() },
        tokens = emptyList(),
    )
}

/** Stamp how the match ended and freeze remaining board positions. */
internal fun MatchDocument.withFinishedBoard(reason: String): MatchDocument {
    return copy(
        winnerReason = reason,
        players = players.map { player ->
            when {
                player.finalTokens != null -> player
                player.tokens.isNotEmpty() -> player.copy(finalTokens = player.tokens)
                else -> player
            }
        },
    )
}

internal fun resolveTokenCell(color: String, progress: Int, tokenIndex: Int): BoardCell {
    if (progress == -1) {
        return yardPositions[color]!![tokenIndex]
    }

    if (progress in 0..MAIN_PATH_LAST_PROGRESS) {
        return boardPath[(startOffsets[color]!! + progress) % 52]
    }

    if (progress in HOME_LANE_START_PROGRESS..HOME_LANE_LAST_PROGRESS) {
        return homeLanes[color]!![progress - HOME_LANE_START_PROGRESS]
    }

    return BoardCell(7, 7)
}

internal fun boardCellKey(color: String, progress: Int, tokenIndex: Int): String {
    val cell = resolveTokenCell(color, progress, tokenIndex)
    return "${cell.row}-${cell.col}"
}

internal fun canMoveToken(progress: Int, diceValue: Int): Boolean {
    if (progress == -1) {
        return diceValue == 6
    }

    if (progress >= FINISHED_PROGRESS) {
        return false
    }

    return progress + diceValue <= FINISHED_PROGRESS
}

internal fun movableTokenIndexes(player: MatchPlayerState, diceValue: Int): List<Int> {
    return player.tokens.mapIndexedNotNull { tokenIndex, progress ->
        tokenIndex.takeIf { canMoveToken(progress, diceValue) }
    }
}

internal fun resolveForcedAutoMoveToken(
    selectableTokenIndexes: List<Int>,
): Int? {
    // Only auto-play when there is exactly one legal move.
    // If a six can also open yard tokens, let the player choose.
    return selectableTokenIndexes.singleOrNull()
}

internal fun resolveTwoPlayerForfeitWinner(
    playersBeforeLeave: List<MatchPlayerState>,
    playersAfterLeave: List<MatchPlayerState>,
): MatchPlayerState? {
    // Only true 2-seat matches forfeit immediately. Mixed human+bot 4-seat matches continue
    // while at least one human remains.
    val activeSeatsBeforeLeave = playersBeforeLeave.count { player -> !player.isEffectivelyAbandoned() }
    val remainingActiveSeats = playersAfterLeave.filter { player -> !player.isEffectivelyAbandoned() }
    if (playersBeforeLeave.size == 2 && activeSeatsBeforeLeave == 2 && remainingActiveSeats.size == 1) {
        return remainingActiveSeats.first()
    }

    return null
}

internal fun countActiveRealHumans(players: List<MatchPlayerState>): Int {
    return players.count { player -> !player.isBot && !player.isEffectivelyAbandoned() }
}

internal fun shouldEndMatchWithNoHumans(playersAfterLeave: List<MatchPlayerState>): Boolean {
    return countActiveRealHumans(playersAfterLeave) == 0
}

internal fun shouldAwardHouseWin(playersAfterLeave: List<MatchPlayerState>): Boolean {
    return shouldEndMatchWithNoHumans(playersAfterLeave)
}

internal fun botProgressScore(player: MatchPlayerState): Int {
    return player.tokens.sumOf { progress -> progress.coerceAtLeast(0) }
}

/**
 * Picks the active bot with the highest board progress. Ties break by userId for stability.
 */
internal fun selectWinningBot(players: List<MatchPlayerState>): MatchPlayerState? {
    return players
        .filter { player -> player.isBot && !player.isEffectivelyAbandoned() }
        .maxWithOrNull(
            compareBy<MatchPlayerState> { botProgressScore(it) }
                .thenBy { it.userId },
        )
}

internal data class AbandonOutcome(
    val forfeitWinner: MatchPlayerState? = null,
    val botWinner: MatchPlayerState? = null,
    val houseWin: Boolean = false,
)

internal fun resolveAbandonOutcome(
    playersBeforeLeave: List<MatchPlayerState>,
    playersAfterLeave: List<MatchPlayerState>,
): AbandonOutcome {
    val forfeitWinner = resolveTwoPlayerForfeitWinner(playersBeforeLeave, playersAfterLeave)
    if (forfeitWinner != null) {
        return AbandonOutcome(forfeitWinner = forfeitWinner)
    }

    if (!shouldEndMatchWithNoHumans(playersAfterLeave)) {
        return AbandonOutcome()
    }

    val botWinner = selectWinningBot(playersAfterLeave)
    if (botWinner != null) {
        return AbandonOutcome(botWinner = botWinner)
    }

    // No humans and no bots left (e.g. last human in an all-human match).
    return AbandonOutcome(houseWin = true)
}

internal data class MissedTurnRegistration(
    val players: List<MatchPlayerState>,
    val autoLeft: Boolean,
    val abandonOutcome: AbandonOutcome = AbandonOutcome(),
)

internal fun registerMissedTurn(
    players: List<MatchPlayerState>,
    playerIndex: Int,
    maxMissedTurns: Int,
    abandonedUserId: String,
): MissedTurnRegistration {
    require(maxMissedTurns > 0) { "maxMissedTurns must be positive." }

    val player = players[playerIndex]
    val nextMissedTurns = player.missedTurns + 1
    val playersWithMiss = players.toMutableList().apply {
        this[playerIndex] = player.copy(missedTurns = nextMissedTurns)
    }

    if (nextMissedTurns < maxMissedTurns) {
        return MissedTurnRegistration(
            players = playersWithMiss,
            autoLeft = false,
        )
    }

    val abandonedPlayers = playersWithMiss.toMutableList().apply {
        this[playerIndex] = this[playerIndex].asAbandoned(abandonedUserId)
    }

    return MissedTurnRegistration(
        players = abandonedPlayers,
        autoLeft = true,
        abandonOutcome = resolveAbandonOutcome(players, abandonedPlayers),
    )
}

internal fun chooseBotToken(
    players: List<MatchPlayerState>,
    playerIndex: Int,
    movableTokenIndexes: List<Int>,
    diceValue: Int,
): Int {
    return com.craft.ludo.gameplay.bot.SuperiorBotEngine.chooseToken(
        players = players,
        playerIndex = playerIndex,
        movableTokenIndexes = movableTokenIndexes,
        diceValue = diceValue,
    )
}

private fun randomDice(consecutiveSixCount: Int = 0): Int {
    return if (consecutiveSixCount >= 2) {
        (1..5).random()
    } else {
        (1..6).random()
    }
}

private fun clientIp(request: ServerHttpRequest): String? {
    val forwardedFor = request.headers.getFirst("X-Forwarded-For")
        ?.split(",")
        ?.firstOrNull()
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    return forwardedFor ?: request.remoteAddress?.address?.hostAddress
}

internal fun isSameSourceRealPlayerSeat(seat: RoomSeat, principal: SessionPrincipal): Boolean {
    if (seat.isBot || seat.isAbandoned) return false
    if (seat.userId == principal.id) return true
    val principalOperatorUserId = principal.operatorUserId.normalizedSource()
    val seatOperatorUserId = seat.operatorUserId.normalizedSource()
    return principalOperatorUserId != null &&
        seatOperatorUserId != null &&
        principalOperatorUserId == seatOperatorUserId
}

internal fun isSameSourceSeatPair(left: RoomSeat, right: RoomSeat): Boolean {
    if (left.isBot || right.isBot || left.isAbandoned || right.isAbandoned) return false
    if (left.userId == right.userId) return true
    val leftOperatorUserId = left.operatorUserId.normalizedSource()
    val rightOperatorUserId = right.operatorUserId.normalizedSource()
    return leftOperatorUserId != null &&
        rightOperatorUserId != null &&
        leftOperatorUserId == rightOperatorUserId
}

internal fun sameSourceReasonForSeat(seat: RoomSeat, principal: SessionPrincipal): String =
    when {
        seat.userId == principal.id -> "same_user_id"
        seat.operatorUserId.normalizedSource() != null &&
            seat.operatorUserId.normalizedSource() == principal.operatorUserId.normalizedSource() -> "same_operator_user_id"
        else -> "unknown"
    }

internal fun sameSourceReasonForSeatPair(left: RoomSeat, right: RoomSeat): String =
    when {
        left.userId == right.userId -> "same_user_id"
        left.operatorUserId.normalizedSource() != null &&
            left.operatorUserId.normalizedSource() == right.operatorUserId.normalizedSource() -> "same_operator_user_id"
        else -> "unknown"
    }

private fun String?.normalizedSource(): String? = this?.trim()?.lowercase()?.takeIf { it.isNotBlank() }

@Component
class AppInstanceCoordinator(
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val clock: Clock,
    appProperties: AppProperties,
) {
    private val log = LoggerFactory.getLogger(AppInstanceCoordinator::class.java)
    private val instanceProperties = appProperties.instance
    val instanceId: String = resolveInstanceId(instanceProperties.id)
    private val lockKeyPrefix = instanceProperties.lockKeyPrefix.trim().ifBlank { "potludo:lock" }
    private val heartbeatKeyPrefix = instanceProperties.heartbeatKeyPrefix.trim().ifBlank { "potludo:instance" }
    private val lockTtl = Duration.ofMillis(instanceProperties.lockTtlMillis)
    private val heartbeatTtl = Duration.ofMillis(instanceProperties.heartbeatTtlMillis)

    init {
        require(instanceProperties.lockTtlMillis > 0) { "app.instance.lock-ttl-millis must be positive." }
        require(instanceProperties.heartbeatTtlMillis > 0) {
            "app.instance.heartbeat-ttl-millis must be positive."
        }
    }

    fun <T : Any> withLock(
        scope: String,
        resourceId: String,
        action: () -> Mono<T>,
    ): Mono<T> {
        val lockKey = "$lockKeyPrefix:$scope:$resourceId"

        return redisTemplate.opsForValue()
            .setIfAbsent(lockKey, instanceId, lockTtl)
            .flatMap { acquired ->
                if (acquired) {
                    actionWithRelease(lockKey, action)
                } else {
                    Mono.empty()
                }
            }
            .onErrorResume { error ->
                log.warn(
                    "Redis lock '{}' could not be acquired by instance '{}'. Running locally.",
                    lockKey,
                    instanceId,
                    error,
                )
                action()
            }
    }

    fun recordHeartbeat(): Mono<Boolean> {
        return redisTemplate.opsForValue()
            .set("$heartbeatKeyPrefix:$instanceId", Instant.now(clock).toString(), heartbeatTtl)
            .onErrorResume { error ->
                log.warn("Redis heartbeat failed for instance '{}'.", instanceId, error)
                Mono.just(false)
            }
    }

    private fun <T : Any> actionWithRelease(
        lockKey: String,
        action: () -> Mono<T>,
    ): Mono<T> {
        return action()
            .flatMap { result -> releaseIfOwned(lockKey).thenReturn(result) }
            .switchIfEmpty(Mono.defer { releaseIfOwned(lockKey).then(Mono.empty<T>()) })
            .onErrorResume { error -> releaseIfOwned(lockKey).then(Mono.error<T>(error)) }
    }

    private fun releaseIfOwned(lockKey: String): Mono<Long> {
        return redisTemplate.opsForValue()
            .get(lockKey)
            .flatMap { owner ->
                if (owner == instanceId) {
                    redisTemplate.delete(lockKey)
                } else {
                    Mono.just(0L)
                }
            }
            .onErrorResume { error ->
                log.warn("Redis lock '{}' could not be released by instance '{}'.", lockKey, instanceId, error)
                Mono.just(0L)
            }
    }

    private fun resolveInstanceId(configuredId: String?): String {
        return configuredId?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: System.getenv("HOSTNAME")?.trim()?.takeIf { it.isNotBlank() }
            ?: System.getenv("COMPUTERNAME")?.trim()?.takeIf { it.isNotBlank() }
            ?: newId("node")
    }
}

@Service
class MatchRealtimeService(
    private val objectMapper: ObjectMapper,
    private val redisTemplate: ReactiveStringRedisTemplate,
    appProperties: AppProperties,
) {
    private val log = LoggerFactory.getLogger(MatchRealtimeService::class.java)
    private val sinks = ConcurrentHashMap<String, Sinks.Many<String>>()
    private val redisChannel = appProperties.realtime.redisChannel
    private val originId = newId("realtime")
    private var redisSubscription: Disposable? = null

    @PostConstruct
    fun startRedisSubscription() {
        redisSubscription = redisTemplate.listenTo(ChannelTopic.of(redisChannel))
            .flatMap { message ->
                decodeRedisMessage(message.message)?.let { decoded ->
                    Mono.just(decoded)
                } ?: Mono.empty()
            }
            .filter { message -> message.originId != originId }
            .doOnNext { message ->
                emitLocal(message.matchId, message.payload)
            }
            .doOnError { error ->
                log.warn("Realtime Redis subscription failed for channel '{}'. Retrying.", redisChannel, error)
            }
            .retryWhen(
                Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(2))
                    .maxBackoff(Duration.ofSeconds(30)),
            )
            .subscribe()
    }

    @PreDestroy
    fun stopRedisSubscription() {
        redisSubscription?.dispose()
    }

    fun stream(matchId: String): Flux<String> {
        return sinks.computeIfAbsent(matchId) {
            Sinks.many().multicast().directBestEffort()
        }.asFlux()
    }

    fun publishMatchSnapshot(match: MatchDocument): Mono<Void> {
        return emit(
            match.id,
            MatchRealtimeEnvelope(
                type = "match_snapshot",
                match = match.toSnapshot(),
            ),
        )
    }

    fun publishSignal(signal: WebRtcSignalEvent): Mono<Void> {
        return emit(
            signal.matchId,
            MatchRealtimeEnvelope(
                type = "webrtc_signal",
                signal = signal,
            ),
        )
    }

    private fun emit(matchId: String, payload: MatchRealtimeEnvelope): Mono<Void> {
        val encoded = objectMapper.writeValueAsString(payload)
        val redisPayload = objectMapper.writeValueAsString(
            MatchRealtimeRedisMessage(
                matchId = matchId,
                payload = encoded,
                originId = originId,
            ),
        )

        emitLocal(matchId, encoded)

        return redisTemplate.convertAndSend(redisChannel, redisPayload)
            .then()
            .onErrorResume { error ->
                log.warn(
                    "Realtime Redis publish failed for match '{}'. Local clients were already notified.",
                    matchId,
                    error,
                )
                Mono.empty()
            }
    }

    private fun decodeRedisMessage(encoded: String): MatchRealtimeRedisMessage? {
        return try {
            objectMapper.readValue(encoded, MatchRealtimeRedisMessage::class.java)
        } catch (error: Exception) {
            log.warn("Ignoring invalid realtime Redis payload.", error)
            null
        }
    }

    private fun emitLocal(matchId: String, encodedPayload: String) {
        val sink = sinks.computeIfAbsent(matchId) {
            Sinks.many().multicast().directBestEffort()
        }
        sink.tryEmitNext(encodedPayload)
    }
}

@Service
class MatchService(
    private val matchRepository: MatchRepository,
    private val roomRepository: RoomRepository,
    private val walletService: WalletService,
    private val realtimeService: MatchRealtimeService,
    private val instanceCoordinator: AppInstanceCoordinator,
    private val clock: Clock,
    appProperties: AppProperties,
) {
    private val log = LoggerFactory.getLogger(MatchService::class.java)
    private val turnTimeoutSeconds = appProperties.gameplay.turnTimeoutSeconds
    private val maxMissedTurns = appProperties.gameplay.maxMissedTurns
    private val houseUserId = appProperties.wallet.houseUserId.trim()
    private val rollDelayMillis = appProperties.gameplay.rollDelayMillis
    private val botMoveDelayMillis = appProperties.gameplay.botMoveDelayMillis
    private val advanceDelayMillis = appProperties.gameplay.advanceDelayMillis
    private val noMoveRollHoldMillis = 700L

    init {
        require(turnTimeoutSeconds > 0) { "app.gameplay.turn-timeout-seconds must be positive." }
        require(maxMissedTurns > 0) {
            "app.gameplay.max-missed-turns must be positive."
        }
        require(houseUserId.isNotBlank()) { "app.wallet.house-user-id must not be blank." }
    }

    fun createStartedMatch(room: RoomDocument): Mono<MatchDocument> {
        require(room.seats.isNotEmpty()) { "Cannot start a match with no room seats." }

        // Concurrent start continuations: if another poller already activated the room, reuse it.
        if (room.matchId != null && room.status == RoomStatus.ACTIVE) {
            return matchRepository.findById(room.matchId)
                .switchIfEmpty(
                    Mono.error(IllegalStateException("Active room ${room.id} is missing match ${room.matchId}")),
                )
        }

        val now = Instant.now(clock)
        val matchId = newId("match")
        log.info(
            "Ludo match creation requested roomId={} roomCode={} mode={} matchId={} seats={} entryFee={} potAmount={}",
            room.id,
            room.code,
            room.mode,
            matchId,
            room.seats.size,
            room.entryFee,
            room.walletReservations.sumOf { it.amount },
        )
        val players = room.seats
            .map { seat ->
                MatchPlayerState(
                    userId = seat.userId,
                    displayName = seat.displayName,
                    color = seat.color,
                    isBot = seat.isBot,
                    isAbandoned = seat.isAbandoned,
                    tokens = listOf(-1, -1, -1, -1),
                    operatorUserId = seat.operatorUserId,
                    operatorId = seat.operatorId,
                )
            }
            .sortedBy { player -> clockwiseColorIndex(player.color) }
        val openingPlayer = players.first()
        val potIntroDelayMillis = 3800L
        val match = MatchDocument(
            id = matchId,
            roomId = room.id,
            roomCode = room.code,
            mode = room.mode,
            status = MatchStatus.ACTIVE,
            phase = MatchPhase.ROLLING,
            entryFee = room.entryFee,
            potAmount = room.walletReservations.sumOf { it.amount },
            turnTimeoutSeconds = turnTimeoutSeconds,
            currentPlayerIndex = 0,
            currentTurnUserId = openingPlayer.userId,
            currentTurnDisplayName = openingPlayer.displayName,
            consecutiveSixCount = 0,
            players = players,
            phaseDeadlineAt = if (openingPlayer.isBot) now.plusMillis(potIntroDelayMillis + rollDelayMillis) else null,
            turnDeadlineAt = now.plusMillis(potIntroDelayMillis + turnTimeoutSeconds * 1000L),
            events = listOf(
                MatchEvent(
                    actor = "System",
                    detail = "Match started in room ${room.code}.",
                    createdAt = now,
                ),
            ),
            createdAt = now,
            updatedAt = now,
        )

        return roomRepository.findById(room.id)
            .flatMap { latestRoom ->
                if (latestRoom.matchId != null && latestRoom.status == RoomStatus.ACTIVE) {
                    return@flatMap matchRepository.findById(latestRoom.matchId)
                        .switchIfEmpty(
                            Mono.error(
                                IllegalStateException(
                                    "Active room ${latestRoom.id} is missing match ${latestRoom.matchId}",
                                ),
                            ),
                        )
                }

                roomRepository.save(
                    latestRoom.copy(
                        status = RoomStatus.ACTIVE,
                        matchId = matchId,
                        seats = room.seats,
                        walletReservations = room.walletReservations,
                        startAttemptId = null,
                        updatedAt = now,
                    ),
                )
                    .flatMap { activeRoom ->
                        matchRepository.save(match)
                            .onErrorResume { error ->
                                log.error(
                                    "Match save failed after room went ACTIVE; reverting room roomId={} matchId={} reason={}",
                                    activeRoom.id,
                                    matchId,
                                    error.message ?: error.javaClass.simpleName,
                                    error,
                                )
                                val rollback = roomRepository.save(
                                    activeRoom.copy(
                                        status = RoomStatus.FINISHED,
                                        matchId = null,
                                        startAttemptId = null,
                                        seats = normalizeSeatColors(
                                            activeRoom.seats.filter { !it.isBot && !it.isAbandoned },
                                        ),
                                        walletReservations = emptyList(),
                                        ownerInstanceId = instanceCoordinator.instanceId,
                                        ownedWaitingDeadlineAt = null,
                                        waitingDeadlineAt = null,
                                        updatedAt = Instant.now(clock),
                                    ),
                                ).then(
                                    matchRepository.findById(matchId)
                                        .flatMap { matchRepository.delete(it) }
                                        .onErrorResume { Mono.empty() },
                                )

                                rollback.then(Mono.error(error))
                            }
                    }
            }
            .flatMap { saved ->
                log.info(
                    "Ludo match started roomId={} roomCode={} matchId={} players={} potAmount={} firstTurnUserId={}",
                    saved.roomId,
                    saved.roomCode,
                    saved.id,
                    saved.players.size,
                    saved.potAmount,
                    saved.currentTurnUserId,
                )
                realtimeService.publishMatchSnapshot(saved).thenReturn(saved)
            }
    }

    fun getMatchForUser(matchId: String, userId: String): Mono<MatchDocument> {
        return matchRepository.findById(matchId)
            .switchIfEmpty(Mono.error(DomainException(HttpStatus.NOT_FOUND, "Match not found.")))
            .flatMap { match ->
                if (match.players.any { it.userId == userId }) {
                    Mono.just(match)
                } else {
                    Mono.error(DomainException(HttpStatus.FORBIDDEN, "User is not part of this match."))
                }
            }
    }

    fun submitMove(matchId: String, principal: SessionPrincipal, tokenIndex: Int): Mono<MatchDocument> {
        if (tokenIndex !in 0..3) {
            return Mono.error(DomainException(HttpStatus.BAD_REQUEST, "tokenIndex must be between 0 and 3."))
        }

        return getMatchForUser(matchId, principal.id)
            .flatMap { match ->
                if (match.status != MatchStatus.ACTIVE) {
                    return@flatMap Mono.error(
                        DomainException(HttpStatus.CONFLICT, "Match is not active."),
                    )
                }

                if (match.phase != MatchPhase.AWAITING_MOVE) {
                    return@flatMap Mono.error(
                        DomainException(HttpStatus.CONFLICT, "Match is not waiting for a human move."),
                    )
                }

                if (match.currentTurnUserId != principal.id) {
                    return@flatMap Mono.error(
                        DomainException(HttpStatus.CONFLICT, "It is not this user's turn."),
                    )
                }

                val activePlayer = match.players[match.currentPlayerIndex]
                if (activePlayer.isEffectivelyAbandoned()) {
                    return@flatMap Mono.error(
                        DomainException(HttpStatus.CONFLICT, "This player is no longer in the match."),
                    )
                }

                if (!match.selectableTokenIndexes.contains(tokenIndex)) {
                    return@flatMap Mono.error(
                        DomainException(HttpStatus.CONFLICT, "Token cannot be moved for the current dice roll."),
                    )
                }

                val now = Instant.now(clock)
                persistMatchTransition(applyTokenMove(match, tokenIndex, now))
            }
    }

    fun rollDice(matchId: String, principal: SessionPrincipal): Mono<MatchDocument> {
        return getMatchForUser(matchId, principal.id)
            .flatMap { match ->
                if (match.status != MatchStatus.ACTIVE) {
                    return@flatMap Mono.error(
                        DomainException(HttpStatus.CONFLICT, "Match is not active."),
                    )
                }

                if (match.phase != MatchPhase.ROLLING) {
                    return@flatMap Mono.error(
                        DomainException(HttpStatus.CONFLICT, "Match is not waiting for a dice roll."),
                    )
                }

                if (match.currentTurnUserId != principal.id) {
                    return@flatMap Mono.error(
                        DomainException(HttpStatus.CONFLICT, "It is not this user's turn."),
                    )
                }

                val activePlayer = match.players[match.currentPlayerIndex]
                if (activePlayer.isEffectivelyAbandoned()) {
                    return@flatMap Mono.error(
                        DomainException(HttpStatus.CONFLICT, "This player is no longer in the match."),
                    )
                }

                if (activePlayer.isBot) {
                    return@flatMap Mono.error(
                        DomainException(HttpStatus.CONFLICT, "Bot turns roll automatically."),
                    )
                }

                val now = Instant.now(clock)
                persistMatchTransition(resolveRoll(match, now))
            }
    }

    fun leaveActiveMatch(room: RoomDocument, userId: String): Mono<Void> {
        if (room.status != RoomStatus.ACTIVE || room.matchId == null) {
            return Mono.error(
                DomainException(HttpStatus.CONFLICT, "Room can only be left while an active match is running."),
            )
        }

        val now = Instant.now(clock)

        return matchRepository.findById(room.matchId)
            .switchIfEmpty(Mono.error(DomainException(HttpStatus.NOT_FOUND, "Match not found.")))
            .flatMap { match ->
                val leavingIndex = match.players.indexOfFirst { player ->
                    player.userId == userId && !player.isBot && !player.isEffectivelyAbandoned()
                }

                if (leavingIndex == -1) {
                    return@flatMap Mono.error(
                        DomainException(HttpStatus.NOT_FOUND, "User is not part of this active match."),
                    )
                }

                val leavingPlayer = match.players[leavingIndex]
                val abandonedId = newId("abandoned")
                val updatedPlayers = match.players.toMutableList().apply {
                    this[leavingIndex] = leavingPlayer.asAbandoned(abandonedId)
                }
                val updatedRoomSeats = room.seats.map { seat ->
                    if (seat.userId == userId && !seat.isBot && !seat.isAbandoned) {
                        seat.copy(
                            userId = abandonedId,
                            isAbandoned = true,
                            joinedAt = now,
                        )
                    } else {
                        seat
                    }
                }

                val abandonedMatch = match.copy(
                    players = updatedPlayers,
                    updatedAt = now,
                    sequence = match.sequence + 1,
                    events = prependEvent(
                        match.events,
                        "System",
                        "${leavingPlayer.displayName} abandoned the game.",
                        now,
                    ),
                )

                val abandonOutcome = resolveAbandonOutcome(match.players, updatedPlayers)
                val updatedMatch = when {
                    abandonOutcome.forfeitWinner != null -> {
                        val winner = abandonOutcome.forfeitWinner
                        abandonedMatch.copy(
                            status = MatchStatus.FINISHED,
                            phase = MatchPhase.FINISHED,
                            winnerUserId = winner.userId,
                            winnerDisplayName = winner.displayName,
                            selectableTokenIndexes = emptyList(),
                            pendingNextPlayerIndex = null,
                            phaseDeadlineAt = null,
                            turnDeadlineAt = null,
                            events = prependEvent(
                                abandonedMatch.events,
                                "System",
                                "${winner.displayName} won because the opponent left the 2-player match.",
                                now,
                            ),
                        ).withFinishedBoard(WinnerReason.FORFEIT)
                    }

                    abandonOutcome.botWinner != null -> {
                        finishMatchAsBotWin(abandonedMatch, abandonOutcome.botWinner, now)
                    }

                    abandonOutcome.houseWin -> finishMatchAsHouseWin(abandonedMatch, now)

                    shouldSkipCurrentTurn(abandonedMatch) -> {
                        skipAbandonedCurrentPlayer(abandonedMatch, now)
                    }

                    else -> abandonedMatch
                }

                val nextRoomStatus =
                    if (updatedMatch.status == MatchStatus.FINISHED) RoomStatus.FINISHED else room.status

                roomRepository.save(
                    room.copy(
                        seats = updatedRoomSeats,
                        status = nextRoomStatus,
                        updatedAt = now,
                    ),
                ).then(persistMatchTransition(updatedMatch)).then()
            }
    }

    fun processDueMatches(): Mono<Void> {
        return matchRepository.findAllByStatus(MatchStatus.ACTIVE)
            .concatMap { match ->
                instanceCoordinator.withLock("match-tick", match.id) {
                    matchRepository.findById(match.id)
                        .flatMap { latestMatch -> processDueMatch(latestMatch, Instant.now(clock)) }
                        .switchIfEmpty(Mono.empty())
                }
            }
            .then()
    }

    private fun processDueMatch(match: MatchDocument, now: Instant): Mono<Void> {
        val work = when {
            match.status != MatchStatus.ACTIVE -> Mono.empty()

            shouldSkipCurrentTurn(match) -> {
                persistMatchTransition(skipAbandonedCurrentPlayer(match, now))
            }

            match.phase == MatchPhase.ROLLING &&
                match.players[match.currentPlayerIndex].isBot &&
                match.phaseDeadlineAt != null &&
                !now.isBefore(match.phaseDeadlineAt) -> {
                persistMatchTransition(resolveRoll(match, now))
            }

            match.phase == MatchPhase.ROLLING &&
                !match.players[match.currentPlayerIndex].isBot &&
                match.turnDeadlineAt != null &&
                !now.isBefore(match.turnDeadlineAt) -> {
                persistHumanMissedTurn(match, now)
            }

            match.phase == MatchPhase.BOT_MOVING &&
                match.phaseDeadlineAt != null &&
                !now.isBefore(match.phaseDeadlineAt) -> {
                // A staged hunt already picked the token the rigged dice was meant for.
                val tokenIndex = match.botForcedTokenIndex
                    ?.takeIf { forced -> match.selectableTokenIndexes.contains(forced) }
                    ?: chooseBotToken(
                        match.players,
                        match.currentPlayerIndex,
                        match.selectableTokenIndexes,
                        match.dice ?: 1,
                    )
                persistMatchTransition(applyTokenMove(match, tokenIndex, now))
            }

            match.phase == MatchPhase.ADVANCING &&
                match.phaseDeadlineAt != null &&
                !now.isBefore(match.phaseDeadlineAt) -> {
                persistMatchTransition(advanceTurn(match, now))
            }

            match.phase == MatchPhase.AWAITING_MOVE &&
                match.turnDeadlineAt != null &&
                !now.isBefore(match.turnDeadlineAt) -> {
                handleHumanTimeout(match, now)
            }

            else -> Mono.empty()
        }

        return work.onErrorResume { Mono.empty() }.then()
    }

    private fun shouldSkipCurrentTurn(match: MatchDocument): Boolean {
        val activePlayer = match.players.getOrNull(match.currentPlayerIndex) ?: return true
        return activePlayer.isEffectivelyAbandoned() ||
            activePlayer.userId != match.currentTurnUserId ||
            match.players.none { player ->
                !player.isEffectivelyAbandoned() && player.userId == match.currentTurnUserId
            }
    }

    private fun skipAbandonedCurrentPlayer(match: MatchDocument, now: Instant): MatchDocument {
        val activePlayerName = match.players
            .getOrNull(match.currentPlayerIndex)
            ?.displayName
            ?: match.currentTurnDisplayName

        return beginTurn(
            match.copy(
                phase = MatchPhase.ROLLING,
                lastRollUserId = null,
                lastRollDisplayName = null,
                lastRollDice = null,
                dice = null,
                selectableTokenIndexes = emptyList(),
                pendingNextPlayerIndex = null,
                phaseDeadlineAt = null,
                events = prependEvent(
                    match.events,
                    "System",
                    "$activePlayerName is no longer in the game.",
                    now,
                ),
            ),
            match.currentPlayerIndex + 1,
            now,
        )
    }

    private fun resolveRoll(
        match: MatchDocument,
        now: Instant,
    ): MatchDocument {
        val botDiceSettings = BotDiceSettings.DEFAULT
        val playerIndex = match.currentPlayerIndex
        val matchForRoll = match
        val rollingPlayer = matchForRoll.players[playerIndex]
        val diceContext = buildDiceRollContext(matchForRoll.players, playerIndex)
        val rollDecision = if (rollingPlayer.isBot) {
            rollBotDice(
                consecutiveSixCount = matchForRoll.consecutiveSixCount,
                players = matchForRoll.players,
                playerIndex = playerIndex,
                settings = botDiceSettings,
                context = diceContext,
                stalkPlan = stalkPlanFor(matchForRoll.botStalkPlans, playerIndex),
            )
        } else {
            BotDiceDecision(
                dice = rollUserDice(
                    consecutiveSixCount = matchForRoll.consecutiveSixCount,
                    players = matchForRoll.players,
                    playerIndex = playerIndex,
                    killFavorPercent = botDiceSettings.userKillFavorFor(matchForRoll.players.size),
                    context = diceContext,
                ),
            )
        }
        val dice = rollDecision.dice
        val playersAfterRoll = incrementPlayerRollStats(matchForRoll.players, playerIndex, dice)
        val selectableTokenIndexes = movableTokenIndexes(rollingPlayer, dice)
        val nextConsecutiveSixCount = if (dice == 6) {
            matchForRoll.consecutiveSixCount + 1
        } else {
            0
        }
        val detail = if (selectableTokenIndexes.isEmpty()) {
            "rolled a $dice but had no valid move."
        } else {
            "rolled a $dice."
        }

        val rolledMatch = matchForRoll.copy(
            players = playersAfterRoll,
            dice = dice,
            lastRollUserId = rollingPlayer.userId,
            lastRollDisplayName = rollingPlayer.displayName,
            lastRollDice = dice,
            consecutiveSixCount = nextConsecutiveSixCount,
            phase = when {
                selectableTokenIndexes.isEmpty() -> MatchPhase.ADVANCING
                rollingPlayer.isBot -> MatchPhase.BOT_MOVING
                else -> MatchPhase.AWAITING_MOVE
            },
            selectableTokenIndexes = selectableTokenIndexes,
            pendingNextPlayerIndex = if (selectableTokenIndexes.isEmpty()) {
                nextPlayerIndex(matchForRoll, dice)
            } else {
                null
            },
            botStalkPlans = if (rollingPlayer.isBot) {
                upsertStalkPlan(matchForRoll.botStalkPlans, playerIndex, rollDecision.stalkPlan)
            } else {
                matchForRoll.botStalkPlans
            },
            botForcedTokenIndex = rollDecision.forcedTokenIndex
                ?.takeIf { tokenIndex -> selectableTokenIndexes.contains(tokenIndex) },
            phaseDeadlineAt = when {
                selectableTokenIndexes.isEmpty() -> now.plusMillis(noMoveRollHoldMillis)
                rollingPlayer.isBot -> now.plusMillis(botMoveDelayMillis)
                else -> null
            },
            turnDeadlineAt = if (
                !rollingPlayer.isBot &&
                selectableTokenIndexes.isNotEmpty()
            ) {
                now.plusSeconds(matchForRoll.turnTimeoutSeconds)
            } else {
                matchForRoll.turnDeadlineAt
            },
            updatedAt = now,
            sequence = matchForRoll.sequence + 1,
            events = prependEvent(matchForRoll.events, rollingPlayer.displayName, detail, now),
        )

        if (rollingPlayer.isBot || selectableTokenIndexes.isEmpty()) {
            return rolledMatch
        }

        val forcedTokenIndex = resolveForcedAutoMoveToken(selectableTokenIndexes)
            ?: return rolledMatch

        return applyTokenMove(rolledMatch, forcedTokenIndex, now)
    }

    internal fun applyTokenMove(match: MatchDocument, tokenIndex: Int, now: Instant): MatchDocument {
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

        val capturedPlayers = mutableListOf<String>()

        if (nextProgress in 0..MAIN_PATH_LAST_PROGRESS) {
            val landingCellKey = boardCellKey(activePlayer.color, nextProgress, tokenIndex)
            if (!safeCellKeys.contains(landingCellKey)) {
                var captured = false
                for (playerIndex in mutablePlayers.indices) {
                    if (playerIndex == match.currentPlayerIndex || captured) {
                        continue
                    }

                    val player = mutablePlayers[playerIndex]
                    val adjustedTokens = player.tokens.toMutableList()
                    for (otherTokenIndex in adjustedTokens.indices) {
                        val progress = adjustedTokens[otherTokenIndex]
                        if (progress in 0..MAIN_PATH_LAST_PROGRESS &&
                            boardCellKey(player.color, progress, otherTokenIndex) == landingCellKey
                        ) {
                            adjustedTokens[otherTokenIndex] = -1
                            capturedPlayers.add(player.displayName)
                            captured = true
                            break
                        }
                    }

                    if (captured) {
                        mutablePlayers[playerIndex] = player.copy(tokens = adjustedTokens)
                    }
                }
            }
        }

        val movedOutOfYard = currentProgress == -1 && nextProgress == 0
        val reachedHome = nextProgress == FINISHED_PROGRESS
        val hasWon = mutablePlayers[match.currentPlayerIndex].tokens.all { it == FINISHED_PROGRESS }

        var detail = if (movedOutOfYard) {
            "rolled a $diceValue and opened token ${tokenIndex + 1}."
        } else {
            "rolled a $diceValue and moved token ${tokenIndex + 1}."
        }

        if (capturedPlayers.isNotEmpty()) {
            detail += " Captured ${capturedPlayers.distinct().joinToString(", ")}."
        }

        if (reachedHome) {
            detail += " Token ${tokenIndex + 1} reached home."
        }

        val extraTurn = capturedPlayers.isNotEmpty() || reachedHome
        val nextPlayerIndex = if (extraTurn) {
            match.currentPlayerIndex
        } else {
            nextPlayerIndex(match, diceValue)
        }

        val nextMatch = match.copy(
            status = if (hasWon) MatchStatus.FINISHED else MatchStatus.ACTIVE,
            phase = if (hasWon) MatchPhase.FINISHED else MatchPhase.ADVANCING,
            players = mutablePlayers.toList(),
            selectableTokenIndexes = emptyList(),
            botForcedTokenIndex = null,
            pendingNextPlayerIndex = if (hasWon) null else nextPlayerIndex,
            phaseDeadlineAt = if (hasWon) null else now.plusMillis(advanceDelayMillis),
            turnDeadlineAt = if (hasWon) null else match.turnDeadlineAt,
            winnerUserId = if (hasWon) activePlayer.userId else null,
            winnerDisplayName = if (hasWon) activePlayer.displayName else null,
            updatedAt = now,
            sequence = match.sequence + 1,
            events = prependEvent(match.events, activePlayer.displayName, detail, now),
        )
        return if (hasWon) nextMatch.withFinishedBoard(WinnerReason.HOME) else nextMatch
    }

    private fun handleHumanTimeout(match: MatchDocument, now: Instant): Mono<MatchDocument> {
        val forcedTokenIndex = resolveForcedAutoMoveToken(match.selectableTokenIndexes)

        // Only auto-move when there is exactly one legal choice.
        // If the player had multiple options and timed out, count a missed turn.
        if (forcedTokenIndex != null) {
            val activePlayer = match.players[match.currentPlayerIndex]
            val timedOutMove = applyTokenMove(match, forcedTokenIndex, now)

            return persistMatchTransition(
                timedOutMove.copy(
                    events = prependEvent(
                        timedOutMove.events,
                        activePlayer.displayName,
                        "timed out, so token ${forcedTokenIndex + 1} was auto-played.",
                        now,
                    ),
                ),
            )
        }

        return persistHumanMissedTurn(match, now)
    }

    private fun persistHumanMissedTurn(match: MatchDocument, now: Instant): Mono<MatchDocument> {
        val playerIndex = match.currentPlayerIndex
        val activePlayer = match.players[playerIndex]
        val abandonedId = newId("abandoned")
        val registration = registerMissedTurn(
            players = match.players,
            playerIndex = playerIndex,
            maxMissedTurns = maxMissedTurns,
            abandonedUserId = abandonedId,
        )
        val nextMatch = applyMissedTurnRegistration(
            match = match,
            registration = registration,
            playerIndex = playerIndex,
            now = now,
        )

        return if (registration.autoLeft) {
            persistMatchWithSeatAbandon(
                nextMatch = nextMatch,
                leavingUserId = activePlayer.userId,
                abandonedId = abandonedId,
            )
        } else {
            persistMatchTransition(nextMatch)
        }
    }

    private fun applyMissedTurnRegistration(
        match: MatchDocument,
        registration: MissedTurnRegistration,
        playerIndex: Int,
        now: Instant,
    ): MatchDocument {
        val activePlayer = match.players[playerIndex]

        if (!registration.autoLeft) {
            val missedTurns = registration.players[playerIndex].missedTurns
            val warningDetail = if (missedTurns >= maxMissedTurns - 1) {
                "ran out of time. One more miss and you will be removed from the match."
            } else {
                "ran out of time."
            }
            return beginTurn(
                match.copy(
                    players = registration.players,
                    dice = null,
                    selectableTokenIndexes = emptyList(),
                    pendingNextPlayerIndex = null,
                    phaseDeadlineAt = null,
                    events = prependEvent(
                        match.events,
                        activePlayer.displayName,
                        warningDetail,
                        now,
                    ),
                ),
                (playerIndex + 1) % match.players.size,
                now,
            )
        }

        val abandonedMatch = match.copy(
            players = registration.players,
            dice = null,
            selectableTokenIndexes = emptyList(),
            pendingNextPlayerIndex = null,
            phaseDeadlineAt = null,
            updatedAt = now,
            sequence = match.sequence + 1,
            events = prependEvent(
                match.events,
                "System",
                "${activePlayer.displayName} was removed after missing $maxMissedTurns turns.",
                now,
            ),
        )

        val abandonOutcome = registration.abandonOutcome
        return when {
            abandonOutcome.forfeitWinner != null -> {
                val forfeitWinner = abandonOutcome.forfeitWinner
                abandonedMatch.copy(
                    status = MatchStatus.FINISHED,
                    phase = MatchPhase.FINISHED,
                    winnerUserId = forfeitWinner.userId,
                    winnerDisplayName = forfeitWinner.displayName,
                    selectableTokenIndexes = emptyList(),
                    pendingNextPlayerIndex = null,
                    phaseDeadlineAt = null,
                    turnDeadlineAt = null,
                    events = prependEvent(
                        abandonedMatch.events,
                        "System",
                        "${forfeitWinner.displayName} won because ${activePlayer.displayName} was auto-removed after missing $maxMissedTurns turns.",
                        now,
                    ),
                ).withFinishedBoard(WinnerReason.FORFEIT_MISSED_TURNS)
            }

            abandonOutcome.botWinner != null -> {
                finishMatchAsBotWin(abandonedMatch, abandonOutcome.botWinner, now)
            }

            abandonOutcome.houseWin -> finishMatchAsHouseWin(abandonedMatch, now)

            shouldSkipCurrentTurn(abandonedMatch) -> skipAbandonedCurrentPlayer(abandonedMatch, now)

            else -> abandonedMatch
        }
    }

    private fun finishMatchAsBotWin(
        match: MatchDocument,
        winningBot: MatchPlayerState,
        now: Instant,
    ): MatchDocument {
        return match.copy(
            status = MatchStatus.FINISHED,
            phase = MatchPhase.FINISHED,
            // Bot is shown as the match winner; wallet settlement treats synthetic bot
            // winners as a house win so entry fees go to the platform.
            winnerUserId = winningBot.userId,
            winnerDisplayName = winningBot.displayName,
            selectableTokenIndexes = emptyList(),
            pendingNextPlayerIndex = null,
            phaseDeadlineAt = null,
            turnDeadlineAt = null,
            updatedAt = now,
            events = prependEvent(
                match.events,
                "System",
                "${winningBot.displayName} won because no real players remained.",
                now,
            ),
        ).withFinishedBoard(WinnerReason.ABANDON_BOT)
    }

    private fun finishMatchAsHouseWin(match: MatchDocument, now: Instant): MatchDocument {
        return match.copy(
            status = MatchStatus.FINISHED,
            phase = MatchPhase.FINISHED,
            winnerUserId = houseUserId,
            winnerDisplayName = "Platform",
            selectableTokenIndexes = emptyList(),
            pendingNextPlayerIndex = null,
            phaseDeadlineAt = null,
            turnDeadlineAt = null,
            updatedAt = now,
            events = prependEvent(
                match.events,
                "System",
                "Match ended because no real players remained. Entry fees went to the platform.",
                now,
            ),
        ).withFinishedBoard(WinnerReason.HOUSE)
    }

    private fun persistMatchWithSeatAbandon(
        nextMatch: MatchDocument,
        leavingUserId: String,
        abandonedId: String,
    ): Mono<MatchDocument> {
        return roomRepository.findById(nextMatch.roomId)
            .flatMap { room ->
                val nextRoomStatus =
                    if (nextMatch.status == MatchStatus.FINISHED) RoomStatus.FINISHED else room.status
                roomRepository.save(
                    room.copy(
                        seats = room.seats.map { seat ->
                            if (seat.userId == leavingUserId && !seat.isBot && !seat.isAbandoned) {
                                seat.copy(
                                    userId = abandonedId,
                                    isAbandoned = true,
                                    joinedAt = nextMatch.updatedAt,
                                )
                            } else {
                                seat
                            }
                        },
                        status = nextRoomStatus,
                        updatedAt = nextMatch.updatedAt,
                    ),
                )
            }
            .then(persistMatchTransition(nextMatch))
    }

    private fun advanceTurn(match: MatchDocument, now: Instant): MatchDocument {
        val nextPlayerIndex = match.pendingNextPlayerIndex ?: match.currentPlayerIndex

        return beginTurn(match, nextPlayerIndex, now)
    }

    private fun beginTurn(match: MatchDocument, playerIndex: Int, now: Instant): MatchDocument {
        val resolvedPlayerIndex = nextAvailablePlayerIndex(match, playerIndex)
        val activePlayer = match.players[resolvedPlayerIndex]

        return match.copy(
            phase = MatchPhase.ROLLING,
            currentPlayerIndex = resolvedPlayerIndex,
            currentTurnUserId = activePlayer.userId,
            currentTurnDisplayName = activePlayer.displayName,
            consecutiveSixCount = if (resolvedPlayerIndex == match.currentPlayerIndex) {
                match.consecutiveSixCount
            } else {
                0
            },
            dice = null,
            selectableTokenIndexes = emptyList(),
            pendingNextPlayerIndex = null,
            botForcedTokenIndex = null,
            phaseDeadlineAt = if (activePlayer.isBot) now.plusMillis(rollDelayMillis) else null,
            turnDeadlineAt = now.plusSeconds(match.turnTimeoutSeconds),
            updatedAt = now,
            sequence = match.sequence + 1,
        )
    }

    private fun nextPlayerIndex(match: MatchDocument, diceValue: Int): Int {
        return if (diceValue == 6) {
            match.currentPlayerIndex
        } else {
            nextAvailablePlayerIndex(match, match.currentPlayerIndex + 1)
        }
    }

    private fun nextAvailablePlayerIndex(match: MatchDocument, startingIndex: Int): Int {
        repeat(match.players.size) { offset ->
            val candidateIndex = (startingIndex + offset) % match.players.size
            if (!match.players[candidateIndex].isEffectivelyAbandoned()) {
                return candidateIndex
            }
        }

        return match.currentPlayerIndex
    }

    private fun prependEvent(
        events: List<MatchEvent>,
        actor: String,
        detail: String,
        now: Instant,
    ): List<MatchEvent> {
        return listOf(
            MatchEvent(
                actor = actor,
                detail = detail,
                createdAt = now,
            ),
        ) + events.take(7)
    }

    private fun persistMatchTransition(nextMatch: MatchDocument): Mono<MatchDocument> {
        val normalizedMatch = if (
            nextMatch.status == MatchStatus.ACTIVE &&
            nextMatch.phase != MatchPhase.FINISHED &&
            shouldSkipCurrentTurn(nextMatch)
        ) {
            skipAbandonedCurrentPlayer(nextMatch, Instant.now(clock))
        } else {
            nextMatch
        }

        return matchRepository.save(normalizedMatch)
            .flatMap { saved ->
                if (saved.status == MatchStatus.FINISHED) {
                    log.info(
                        "Ludo match finished roomId={} roomCode={} matchId={} winnerUserId={} winnerDisplayName={} potAmount={}",
                        saved.roomId,
                        saved.roomCode,
                        saved.id,
                        saved.winnerUserId,
                        saved.winnerDisplayName,
                        saved.potAmount,
                    )
                }
                val roomUpdate = if (saved.status == MatchStatus.FINISHED) {
                    roomRepository.findById(saved.roomId)
                        .flatMap { room ->
                            val winnerUserId = saved.winnerUserId
                            val settlement = if (winnerUserId.isNullOrBlank()) {
                                log.warn(
                                    "Finished match '{}' is missing a winner. Finalizing room '{}' without payout.",
                                    saved.id,
                                    room.id,
                                )
                                Mono.empty()
                            } else {
                                walletService.payoutWinner(
                                    matchId = saved.id,
                                    winnerUserId = winnerUserId,
                                    reservations = room.walletReservations,
                                    roomId = room.id,
                                )
                                    .doOnSubscribe {
                                        log.info(
                                            "Ludo winner payout settlement requested roomId={} roomCode={} matchId={} winnerUserId={} reservations={}",
                                            room.id,
                                            room.code,
                                            saved.id,
                                            winnerUserId,
                                            room.walletReservations.size,
                                        )
                                    }
                                    .onErrorResume { error ->
                                        log.warn(
                                            "Winner payout failed for finished match '{}'. Finalizing room '{}' to end the game.",
                                            saved.id,
                                            room.id,
                                            error,
                                        )
                                        Mono.empty()
                                    }
                            }

                            settlement.then(
                                if (room.status == RoomStatus.FINISHED) {
                                    Mono.just(room)
                                } else {
                                    roomRepository.save(
                                        room.copy(
                                            status = RoomStatus.FINISHED,
                                            updatedAt = saved.updatedAt,
                                        ),
                                    )
                                },
                            )
                        }
                        .then()
                } else {
                    Mono.empty()
                }

                roomUpdate.then(realtimeService.publishMatchSnapshot(saved)).thenReturn(saved)
            }
    }
}

@Service
class LobbyService(
    private val roomRepository: RoomRepository,
    private val matchRepository: MatchRepository,
    private val matchService: MatchService,
    private val walletService: WalletService,
    private val onlineMatchmakingService: OnlineMatchmakingService,
    private val instanceCoordinator: AppInstanceCoordinator,
    private val mongoTemplate: ReactiveMongoTemplate,
    private val clock: Clock,
    appProperties: AppProperties,
) {
    private val log = LoggerFactory.getLogger(LobbyService::class.java)
    private val privateRoomMaxPlayers = appProperties.gameplay.roomMaxPlayers
    private val onlineEntryFee = appProperties.gameplay.onlineEntryFee
    private val lobbyWaitMillis = appProperties.gameplay.lobbyWaitMillis
    private val onlinePvpRealPlayerThreshold = appProperties.gameplay.onlinePvpRealPlayerThreshold

    init {
        require(privateRoomMaxPlayers == 4) { "Private Ludo rooms currently require app.gameplay.room-max-players to be 4." }
        require(onlineEntryFee > 0) { "app.gameplay.online-entry-fee must be positive." }
        require(lobbyWaitMillis > 0) { "app.gameplay.lobby-wait-millis must be positive." }
        require(onlinePvpRealPlayerThreshold >= 0) {
            "app.gameplay.online-pvp-real-player-threshold cannot be negative."
        }
    }

    fun listRooms(): Flux<RoomSummaryResponse> {
        return roomRepository.findAll(Sort.by(Sort.Direction.DESC, "updatedAt"))
            .map(RoomDocument::toSummary)
    }

    fun joinOnlineMatch(principal: SessionPrincipal, requestedMaxPlayers: Int?): Mono<JoinOnlineMatchResponse> {
        return onlineMatchmakingService.joinOnlineMatch(principal, requestedMaxPlayers)
    }

    fun processDueWaitingRooms(): Mono<Void> {
        return onlineMatchmakingService.processDueWaitingRooms()
    }

    fun leaveWaitingLobby(userId: String): Mono<Void> {
        return onlineMatchmakingService.leaveOnlineRoom(userId)
            .onErrorResume(DomainException::class.java) { error ->
                if (error.status == HttpStatus.NOT_FOUND) {
                    Mono.error(
                        DomainException(HttpStatus.NOT_FOUND, "Waiting lobby not found for user."),
                    )
                } else {
                    Mono.error(error)
                }
            }
    }

    fun leaveOnlineRoom(userId: String): Mono<Void> {
        return onlineMatchmakingService.leaveOnlineRoom(userId)
    }

    fun getPrivateRoomState(principal: SessionPrincipal): Mono<PrivateRoomStateResponse> {
        return findExistingPrivateRoomForUser(principal.id)
            .switchIfEmpty(
                Mono.error(DomainException(HttpStatus.NOT_FOUND, "Private room not found for user.")),
            )
            .flatMap { room -> buildPrivateRoomState(room, principal.sessionToken) }
    }

    fun createPrivateRoom(
        principal: SessionPrincipal,
        request: CreatePrivateRoomRequest,
    ): Mono<PrivateRoomStateResponse> {
        log.info(
            "Ludo private lobby create requested userId={} displayName={} requestedRoomName={} requestedEntryFee={}",
            principal.id,
            principal.displayName,
            request.roomName,
            request.entryFee,
        )
        return findExistingPrivateRoomForUser(principal.id)
            .flatMap { room ->
                finishRoomIfMatchAlreadyFinished(room)
                    .flatMap { finished ->
                        if (finished) {
                            Mono.empty<PrivateRoomStateResponse>()
                        } else {
                            Mono.error<PrivateRoomStateResponse>(
                                DomainException(HttpStatus.CONFLICT, "Leave the current private room before creating a new one."),
                            )
                        }
                    }
            }
            .switchIfEmpty(
                Mono.defer {
                    val now = Instant.now(clock)
                    val displayName = normalizeRequestedDisplayName(request.displayName, principal.displayName)
                    val roomName = normalizeRequestedRoomName(request.roomName)
                    val entryFee = normalizeRequestedEntryFee(request.entryFee, onlineEntryFee)
                    walletService.requireSufficientBalance(principal.id, entryFee)
                        .then(
                            Mono.defer {
                                val room = RoomDocument(
                                    mode = RoomMode.PRIVATE_FRIENDS,
                                    status = RoomStatus.WAITING,
                                    maxPlayers = privateRoomMaxPlayers,
                                    entryFee = entryFee,
                                    roomName = roomName,
                                    hostUserId = principal.id,
                                    ownerInstanceId = instanceCoordinator.instanceId,
                                    createdAt = now,
                                    updatedAt = now,
                                    seats = normalizeSeatColors(
                                        listOf(
                                            RoomSeat(
                                                userId = principal.id,
                                                displayName = displayName,
                                                color = "",
                                                isBot = false,
                                                joinedAt = now,
                                                ipAddress = principal.ipAddress,
                                                operatorUserId = principal.operatorUserId,
                                                operatorId = principal.operatorId,
                                            ),
                                        ),
                                    ),
                                )

                                roomRepository.save(room)
                                    .doOnNext { savedRoom ->
                                        log.info(
                                            "Ludo private lobby created roomId={} roomCode={} hostUserId={} entryFee={} maxPlayers={}",
                                            savedRoom.id,
                                            savedRoom.code,
                                            savedRoom.hostUserId,
                                            savedRoom.entryFee,
                                            savedRoom.maxPlayers,
                                        )
                                    }
                                    .flatMap { savedRoom -> buildPrivateRoomState(savedRoom, principal.sessionToken) }
                            },
                        )
                }
            )
    }

    fun joinPrivateRoom(
        principal: SessionPrincipal,
        request: JoinPrivateRoomRequest,
    ): Mono<PrivateRoomStateResponse> {
        log.info(
            "Ludo private lobby join requested userId={} displayName={} roomCode={}",
            principal.id,
            principal.displayName,
            request.roomCode,
        )
        return findExistingPrivateRoomForUser(principal.id)
            .flatMap { room ->
                finishRoomIfMatchAlreadyFinished(room)
                    .flatMap { finished ->
                        if (finished) {
                            Mono.empty<PrivateRoomStateResponse>()
                        } else {
                            buildPrivateRoomState(room, principal.sessionToken)
                        }
                    }
            }
            .switchIfEmpty(
                findPrivateRoomByCode(request.roomCode)
                    .flatMap { room ->
                        if (room.status != RoomStatus.WAITING || room.matchId != null) {
                            return@flatMap Mono.error(
                                DomainException(HttpStatus.CONFLICT, "Private room can no longer be joined."),
                            )
                        }

                        if (room.seats.size >= room.maxPlayers) {
                            return@flatMap Mono.error(
                                DomainException(HttpStatus.CONFLICT, "Private room is already full."),
                            )
                        }

                        rejectSameSourceRealPlayer(room.seats, principal, room.id, room.code)

                        walletService.requireSufficientBalance(principal.id, room.entryFee)
                            .then(
                                Mono.defer {
                                    val now = Instant.now(clock)
                                    val updatedSeats = room.seats.toMutableList().apply {
                                        add(
                                            RoomSeat(
                                                userId = principal.id,
                                                displayName = normalizeRequestedDisplayName(request.displayName, principal.displayName),
                                                color = "",
                                                isBot = false,
                                                joinedAt = now,
                                                ipAddress = principal.ipAddress,
                                                operatorUserId = principal.operatorUserId,
                                                operatorId = principal.operatorId,
                                            ),
                                        )
                                    }

                                    roomRepository.save(
                                        room.copy(
                                            seats = normalizeSeatColors(updatedSeats),
                                            updatedAt = now,
                                        ),
                                    ).doOnNext { savedRoom ->
                                        log.info(
                                            "Ludo private lobby joined roomId={} roomCode={} userId={} occupiedSeats={} maxPlayers={}",
                                            savedRoom.id,
                                            savedRoom.code,
                                            principal.id,
                                            savedRoom.seats.size,
                                            savedRoom.maxPlayers,
                                        )
                                    }.flatMap { savedRoom ->
                                        buildPrivateRoomState(savedRoom, principal.sessionToken)
                                    }
                                },
                            )
                    },
            )
    }

    fun transferPrivateRoomHost(
        principal: SessionPrincipal,
        request: TransferPrivateRoomHostRequest,
    ): Mono<PrivateRoomStateResponse> {
        return findExistingPrivateRoomForUser(principal.id)
            .switchIfEmpty(
                Mono.error(DomainException(HttpStatus.NOT_FOUND, "Private room not found for user.")),
            )
            .flatMap { room ->
                if (room.status != RoomStatus.WAITING || room.matchId != null) {
                    return@flatMap Mono.error(
                        DomainException(HttpStatus.CONFLICT, "Host can only be changed before the match starts."),
                    )
                }

                if (room.hostUserId != principal.id) {
                    return@flatMap Mono.error(
                        DomainException(HttpStatus.FORBIDDEN, "Only the current host can transfer room ownership."),
                    )
                }

                val targetSeat = room.seats.firstOrNull { seat ->
                    !seat.isBot && seat.userId == request.targetUserId
                } ?: return@flatMap Mono.error(
                    DomainException(HttpStatus.NOT_FOUND, "Selected player is not in the room."),
                )

                roomRepository.save(
                    room.copy(
                        hostUserId = targetSeat.userId,
                        updatedAt = Instant.now(clock),
                    ),
                ).flatMap { savedRoom ->
                    buildPrivateRoomState(savedRoom, principal.sessionToken)
                }
            }
    }

    fun startPrivateRoom(principal: SessionPrincipal): Mono<PrivateRoomStateResponse> {
        log.info("Ludo private lobby start requested userId={}", principal.id)
        return findExistingPrivateRoomForUser(principal.id)
            .switchIfEmpty(
                Mono.error(DomainException(HttpStatus.NOT_FOUND, "Private room not found for user.")),
            )
            .flatMap { room ->
                if (room.status != RoomStatus.WAITING || room.matchId != null) {
                    return@flatMap Mono.error(
                        DomainException(HttpStatus.CONFLICT, "Private room has already started."),
                    )
                }

                if (room.hostUserId != principal.id) {
                    return@flatMap Mono.error(
                        DomainException(HttpStatus.FORBIDDEN, "Only the host can start the room."),
                    )
                }

                val realSeats = randomizeSeatColors(room.seats.filter { !it.isBot })
                if (realSeats.size < 2) {
                    return@flatMap Mono.error(
                        DomainException(HttpStatus.CONFLICT, "At least 2 players are required to start a private room."),
                    )
                }
                rejectSameSourceSeatGroup(realSeats, room.id, room.code)

                val now = Instant.now(clock)
                val preparedRoom = room.copy(
                    seats = realSeats,
                    updatedAt = now,
                    waitingDeadlineAt = null,
                )
                log.info(
                    "Ludo private lobby start preparing roomId={} roomCode={} hostUserId={} realSeats={} entryFee={}",
                    preparedRoom.id,
                    preparedRoom.code,
                    preparedRoom.hostUserId,
                    realSeats.size,
                    preparedRoom.entryFee,
                )

                roomRepository.save(preparedRoom)
                    .flatMap { savedPreparedRoom ->
                        val existingReservations = savedPreparedRoom.walletReservations
                        val seatsNeedingReservation = realSeats.filter { seat ->
                            existingReservations.none { reservation ->
                                !reservation.synthetic &&
                                    reservation.userId == seat.userId &&
                                    reservation.amount == savedPreparedRoom.entryFee
                            }
                        }

                        Flux.fromIterable(seatsNeedingReservation)
                            .concatMap { seat ->
                                walletService.requireSufficientBalance(seat.userId, savedPreparedRoom.entryFee)
                            }
                            .then(
                                reserveEntryFeesForSeats(
                                    seatsNeedingReservation,
                                    savedPreparedRoom.id,
                                    savedPreparedRoom.entryFee,
                                ),
                            )
                            .flatMap { newReservations ->
                                val startWorkflow = roomRepository.save(
                                    savedPreparedRoom.copy(walletReservations = existingReservations + newReservations),
                                )
                                    .flatMap { savedRoom ->
                                        matchService.createStartedMatch(savedRoom)
                                            .then(roomRepository.findById(savedRoom.id))
                                    }
                                startWorkflow.onErrorResume { error ->
                                    refundReservationsAfterFailedStart(newReservations, savedPreparedRoom.id)
                                        .then(Mono.error(error))
                                }
                            }
                    }
                    .flatMap { startedRoom -> buildPrivateRoomState(startedRoom, principal.sessionToken) }
            }
    }

    private fun findExistingPrivateRoomForUser(userId: String): Mono<RoomDocument> {
        val query = Query.query(
            Criteria.where("mode").`is`(RoomMode.PRIVATE_FRIENDS)
                .and("status").`in`(RoomStatus.WAITING, RoomStatus.STARTING, RoomStatus.ACTIVE)
                .and("seats").elemMatch(
                    Criteria.where("userId").`is`(userId)
                        .and("isAbandoned").ne(true)
                        .and("isBot").ne(true),
                ),
        )

        return mongoTemplate.findOne(query, RoomDocument::class.java)
    }

    private fun findPrivateRoomByCode(roomCode: String): Mono<RoomDocument> {
        val normalizedCode = roomCode.trim().uppercase()
        if (normalizedCode.isBlank()) {
            return Mono.error(DomainException(HttpStatus.BAD_REQUEST, "Room code is required."))
        }

        val query = Query.query(
            Criteria.where("mode").`is`(RoomMode.PRIVATE_FRIENDS)
                .and("code").`is`(normalizedCode)
                .and("status").`in`(RoomStatus.WAITING, RoomStatus.STARTING, RoomStatus.ACTIVE),
        )

        return mongoTemplate.findOne(query, RoomDocument::class.java)
            .switchIfEmpty(Mono.error(DomainException(HttpStatus.NOT_FOUND, "Private room code not found.")))
    }

    private fun rejectSameSourceRealPlayer(
        existingSeats: List<RoomSeat>,
        principal: SessionPrincipal,
        roomId: String,
        roomCode: String,
    ) {
        val conflict = existingSeats.firstOrNull { seat -> isSameSourceRealPlayer(seat, principal) } ?: return
        log.warn(
            "Blocked Ludo same-source room join roomId={} roomCode={} userId={} existingUserId={} reason={}",
            roomId,
            roomCode,
            principal.id,
            conflict.userId,
            sameSourceReason(conflict, principal),
        )
        throw DomainException(
            HttpStatus.CONFLICT,
            "This Ludo room already has a player from the same source. Please join another Ludo room.",
        )
    }

    private fun rejectSameSourceSeatGroup(realSeats: List<RoomSeat>, roomId: String, roomCode: String) {
        realSeats.forEachIndexed { index, seat ->
            realSeats.drop(index + 1).firstOrNull { other -> isSameSourceSeat(seat, other) }?.let { conflict ->
                log.warn(
                    "Blocked Ludo same-source private room start roomId={} roomCode={} userId={} conflictUserId={} reason={}",
                    roomId,
                    roomCode,
                    seat.userId,
                    conflict.userId,
                    sameSourceReason(seat, conflict),
                )
                throw DomainException(
                    HttpStatus.CONFLICT,
                    "This Ludo private room has two players from the same source. Remove one player before starting.",
                )
            }
        }
    }

    private fun hasSameSourceRealPlayer(existingSeats: List<RoomSeat>, principal: SessionPrincipal): Boolean =
        existingSeats.any { seat -> isSameSourceRealPlayer(seat, principal) }

    private fun isSameSourceRealPlayer(seat: RoomSeat, principal: SessionPrincipal): Boolean =
        isSameSourceRealPlayerSeat(seat, principal)

    private fun isSameSourceSeat(left: RoomSeat, right: RoomSeat): Boolean =
        isSameSourceSeatPair(left, right)

    private fun sameSourceReason(seat: RoomSeat, principal: SessionPrincipal): String =
        sameSourceReasonForSeat(seat, principal)

    private fun sameSourceReason(left: RoomSeat, right: RoomSeat): String =
        sameSourceReasonForSeatPair(left, right)

    private fun buildPrivateRoomState(room: RoomDocument, sessionToken: String): Mono<PrivateRoomStateResponse> {
        val matchId = room.matchId
        if (room.status != RoomStatus.ACTIVE || matchId == null) {
            return Mono.just(room.toPrivateState())
        }

        return matchRepository.findById(matchId)
            .switchIfEmpty(Mono.error(DomainException(HttpStatus.NOT_FOUND, "Match not found.")))
            .flatMap { match ->
                if (match.status == MatchStatus.FINISHED || match.phase == MatchPhase.FINISHED) {
                    settleAndMarkRoomFinished(room, match)
                        .then(Mono.error(DomainException(HttpStatus.NOT_FOUND, "Private room not found for user.")))
                } else {
                    Mono.just(
                        room.toPrivateState(
                            match = match,
                            websocketPath = "/ws/matches/${match.id}?sessionToken=$sessionToken",
                        ),
                    )
                }
            }
    }

    private fun finishRoomIfMatchAlreadyFinished(room: RoomDocument): Mono<Boolean> {
        val matchId = room.matchId ?: return Mono.just(false)
        if (room.status != RoomStatus.ACTIVE) return Mono.just(false)

        return matchRepository.findById(matchId)
            .flatMap { match ->
                if (match.status == MatchStatus.FINISHED || match.phase == MatchPhase.FINISHED) {
                    settleAndMarkRoomFinished(room, match).thenReturn(true)
                } else {
                    Mono.just(false)
                }
            }
            .defaultIfEmpty(false)
    }

    private fun settleAndMarkRoomFinished(room: RoomDocument, match: MatchDocument): Mono<RoomDocument> {
        if (room.status == RoomStatus.FINISHED) {
            return Mono.just(room)
        }
        log.info(
            "Ludo room settlement requested roomId={} roomCode={} matchId={} winnerUserId={} reservations={}",
            room.id,
            room.code,
            match.id,
            match.winnerUserId,
            room.walletReservations.size,
        )

        val settlement = match.winnerUserId
            ?.let { winnerUserId ->
                walletService.payoutWinner(
                    matchId = match.id,
                    winnerUserId = winnerUserId,
                    reservations = room.walletReservations,
                    roomId = room.id,
                )
            }
            ?: Mono.empty()

        return settlement
            .onErrorResume { error ->
                log.warn(
                    "Recovered finished match '{}' but settlement retry failed. Finalizing room '{}' to unblock players.",
                    match.id,
                    room.id,
                    error,
                )
                Mono.empty()
            }
            .then(
                roomRepository.save(
                    room.copy(
                        status = RoomStatus.FINISHED,
                        updatedAt = match.updatedAt,
                    ),
                ),
            )
            .doOnNext { savedRoom ->
                log.info(
                    "Ludo room settlement completed roomId={} roomCode={} matchId={} status={}",
                    savedRoom.id,
                    savedRoom.code,
                    match.id,
                    savedRoom.status,
                )
            }
    }


    private fun refundReservationsAfterFailedStart(
        reservations: List<WalletReservation>,
        roomId: String,
    ): Mono<Void> {
        if (reservations.isEmpty()) return Mono.empty()

        return Flux.fromIterable(reservations)
            .concatMap { reservation ->
                walletService.refundReservation(reservation, roomId)
                    .onErrorResume { error ->
                        log.warn(
                            "Failed to refund reservation after room start failure roomId={} userId={} transactionId={} reason={}",
                            roomId,
                            reservation.userId,
                            reservation.transactionId,
                            error.message ?: error.javaClass.simpleName,
                        )
                        Mono.empty()
                    }
            }
            .then()
    }

    fun leavePrivateRoom(userId: String): Mono<Void> {
        return findExistingPrivateRoomForUser(userId)
            .switchIfEmpty(
                Mono.error(DomainException(HttpStatus.NOT_FOUND, "Private room not found for user.")),
            )
            .flatMap { room ->
                when {
                    room.status == RoomStatus.ACTIVE && room.matchId != null -> {
                        matchService.leaveActiveMatch(room, userId)
                    }

                    room.status != RoomStatus.WAITING || room.matchId != null -> {
                        Mono.error(DomainException(HttpStatus.CONFLICT, "Room can no longer be left."))
                    }

                    else -> {
                        val remainingSeats = room.seats
                            .filter { seat -> !seat.isBot && seat.userId != userId }
                            .sortedBy { it.joinedAt }

                        if (remainingSeats.isEmpty()) {
                            roomRepository.delete(room).then()
                        } else {
                            roomRepository.save(
                                room.copy(
                                    seats = normalizeSeatColors(remainingSeats),
                                    hostUserId = if (room.hostUserId == userId) {
                                        remainingSeats.first().userId
                                    } else {
                                        room.hostUserId
                                    },
                                    updatedAt = Instant.now(clock),
                                ),
                            ).then()
                        }
                    }
                }
            }
    }

    private fun reserveEntryFeesForSeats(
        realSeats: List<RoomSeat>,
        roomId: String,
        amount: Long,
        startAttemptId: String? = null,
    ): Mono<List<WalletReservation>> {
        val reservations = mutableListOf<WalletReservation>()
        log.info(
            "Ludo entry fee reservation batch requested roomId={} realSeats={} amount={} startAttemptId={}",
            roomId,
            realSeats.size,
            amount,
            startAttemptId,
        )

        return Flux.fromIterable(realSeats)
            .concatMap { seat ->
                walletService.reserveEntryFee(
                    seat.userId,
                    roomId,
                    amount,
                    seat.ipAddress,
                    startAttemptId,
                )
                    .doOnNext { reservation -> reservations.add(reservation) }
            }
            .collectList()
            .doOnNext { completedReservations ->
                log.info(
                    "Ludo entry fee reservation batch completed roomId={} reservations={} amount={}",
                    roomId,
                    completedReservations.size,
                    amount,
                )
            }
            .onErrorResume { error ->
                log.error(
                    "Ludo entry fee reservation batch failed roomId={} completedReservations={} amount={} reason={}",
                    roomId,
                    reservations.size,
                    amount,
                    error.message ?: error.javaClass.simpleName,
                    error,
                )
                Flux.fromIterable(reservations)
                    .concatMap { reservation ->
                        walletService.refundReservation(reservation, roomId)
                            .onErrorResume { Mono.empty() }
                    }
                    .then(Mono.error(error))
            }
    }

    private fun syntheticEntryFeesForBotSeats(
        botSeats: List<RoomSeat>,
        amount: Long,
        existingReservations: List<WalletReservation>,
    ): List<WalletReservation> {
        if (amount <= 0) return emptyList()

        return botSeats.filter { seat ->
            existingReservations.none { reservation ->
                reservation.synthetic &&
                    reservation.userId == seat.userId &&
                    reservation.amount == amount
            }
        }.map { seat ->
            WalletReservation(
                userId = seat.userId,
                transactionId = newId("botfee"),
                amount = amount,
                synthetic = true,
                ipAddress = seat.ipAddress,
            )
        }
    }
}

@Component
class MatchLifecycleScheduler(
    private val matchService: MatchService,
    private val lobbyService: LobbyService,
    private val instanceCoordinator: AppInstanceCoordinator,
) {
    private val log = LoggerFactory.getLogger(MatchLifecycleScheduler::class.java)
    private val tickInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    @Scheduled(fixedDelay = 150)
    fun tickMatches() {
        if (!tickInFlight.compareAndSet(false, true)) {
            return
        }

        instanceCoordinator.recordHeartbeat()
            .onErrorResume { Mono.empty() }
            .then(lobbyService.processDueWaitingRooms())
            .then(matchService.processDueMatches())
            .doOnError { error ->
                log.error(
                    "Match lifecycle tick failed reason={}",
                    error.message ?: error.javaClass.simpleName,
                    error,
                )
            }
            .doFinally { tickInFlight.set(false) }
            .subscribe(
                {},
                { error ->
                    log.error(
                        "Match lifecycle tick subscription failed reason={}",
                        error.message ?: error.javaClass.simpleName,
                        error,
                    )
                },
            )
    }
}

@RestController
@RequestMapping("/api/v1/lobby", produces = [MediaType.APPLICATION_JSON_VALUE])
class LobbyController(
    private val sessionPrincipalResolver: SessionPrincipalResolver,
    private val lobbyService: LobbyService,
) {
    @GetMapping("/rooms")
    fun listRooms(): Flux<RoomSummaryResponse> = lobbyService.listRooms()

    @PostMapping("/online/join")
    fun joinOnlineMatch(
        @RequestHeader("X-Session-Token") sessionToken: String,
        @RequestBody(required = false) requestBody: JoinOnlineMatchRequest?,
        request: ServerHttpRequest,
    ): Mono<JoinOnlineMatchResponse> {
        return sessionPrincipalResolver.requireUser(sessionToken)
            .flatMap { principal ->
                lobbyService.joinOnlineMatch(
                    principal.copy(ipAddress = clientIp(request)),
                    requestBody?.maxPlayers,
                )
            }
    }

    @PostMapping("/online/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun leaveOnlineLobby(
        @RequestHeader("X-Session-Token") sessionToken: String,
    ): Mono<Void> {
        return sessionPrincipalResolver.requireUser(sessionToken)
            .flatMap { principal -> lobbyService.leaveOnlineRoom(principal.id) }
    }

    @GetMapping("/private/current")
    fun getPrivateRoomState(
        @RequestHeader("X-Session-Token") sessionToken: String,
    ): Mono<PrivateRoomStateResponse> {
        return sessionPrincipalResolver.requireUser(sessionToken)
            .flatMap(lobbyService::getPrivateRoomState)
    }

    @PostMapping("/private/create")
    fun createPrivateRoom(
        @RequestHeader("X-Session-Token") sessionToken: String,
        @RequestBody request: CreatePrivateRoomRequest,
        httpRequest: ServerHttpRequest,
    ): Mono<PrivateRoomStateResponse> {
        return sessionPrincipalResolver.requireUser(sessionToken)
            .flatMap { principal ->
                lobbyService.createPrivateRoom(principal.copy(ipAddress = clientIp(httpRequest)), request)
            }
    }

    @PostMapping("/private/join")
    fun joinPrivateRoom(
        @RequestHeader("X-Session-Token") sessionToken: String,
        @RequestBody request: JoinPrivateRoomRequest,
        httpRequest: ServerHttpRequest,
    ): Mono<PrivateRoomStateResponse> {
        return sessionPrincipalResolver.requireUser(sessionToken)
            .flatMap { principal ->
                lobbyService.joinPrivateRoom(principal.copy(ipAddress = clientIp(httpRequest)), request)
            }
    }

    @PostMapping("/private/host")
    fun transferPrivateRoomHost(
        @RequestHeader("X-Session-Token") sessionToken: String,
        @RequestBody request: TransferPrivateRoomHostRequest,
    ): Mono<PrivateRoomStateResponse> {
        return sessionPrincipalResolver.requireUser(sessionToken)
            .flatMap { principal -> lobbyService.transferPrivateRoomHost(principal, request) }
    }

    @PostMapping("/private/start")
    fun startPrivateRoom(
        @RequestHeader("X-Session-Token") sessionToken: String,
    ): Mono<PrivateRoomStateResponse> {
        return sessionPrincipalResolver.requireUser(sessionToken)
            .flatMap(lobbyService::startPrivateRoom)
    }

    @PostMapping("/private/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun leavePrivateRoom(
        @RequestHeader("X-Session-Token") sessionToken: String,
    ): Mono<Void> {
        return sessionPrincipalResolver.requireUser(sessionToken)
            .flatMap { principal -> lobbyService.leavePrivateRoom(principal.id) }
    }
}

@RestController
@RequestMapping("/api/v1/matches", produces = [MediaType.APPLICATION_JSON_VALUE])
class MatchController(
    private val sessionPrincipalResolver: SessionPrincipalResolver,
    private val matchService: MatchService,
) {
    @GetMapping("/{matchId}")
    fun getMatch(
        @RequestHeader("X-Session-Token") sessionToken: String,
        @PathVariable matchId: String,
    ): Mono<MatchSnapshotResponse> {
        return sessionPrincipalResolver.requireUser(sessionToken)
            .flatMap { principal -> matchService.getMatchForUser(matchId, principal.id) }
            .map(MatchDocument::toSnapshot)
    }

    @PostMapping("/{matchId}/moves")
    fun submitMove(
        @RequestHeader("X-Session-Token") sessionToken: String,
        @PathVariable matchId: String,
        @RequestBody request: MoveTokenRequest,
    ): Mono<MatchSnapshotResponse> {
        return sessionPrincipalResolver.requireUser(sessionToken)
            .flatMap { principal ->
                matchService.submitMove(matchId, principal, request.tokenIndex)
            }
            .map(MatchDocument::toSnapshot)
    }

    @PostMapping("/{matchId}/roll")
    fun rollDice(
        @RequestHeader("X-Session-Token") sessionToken: String,
        @PathVariable matchId: String,
    ): Mono<MatchSnapshotResponse> {
        return sessionPrincipalResolver.requireUser(sessionToken)
            .flatMap { principal ->
                matchService.rollDice(matchId, principal)
            }
            .map(MatchDocument::toSnapshot)
    }
}

@RestController
@RequestMapping("/api/v1/realtime", produces = [MediaType.APPLICATION_JSON_VALUE])
class RealtimeController(
    private val sessionPrincipalResolver: SessionPrincipalResolver,
    private val matchService: MatchService,
    private val realtimeService: MatchRealtimeService,
    appProperties: AppProperties,
) {
    private val webRtcProperties = appProperties.realtime.webRtc

    @GetMapping("/webrtc/config")
    fun getWebRtcConfig(): Mono<WebRtcConfigResponse> {
        return Mono.just(
            WebRtcConfigResponse(
                iceServers = listOf(
                    IceServerResponse(
                        urls = webRtcProperties.iceServers,
                        username = webRtcProperties.turnUsername,
                        credential = webRtcProperties.turnCredential,
                    ),
                ),
            ),
        )
    }

    @PostMapping("/webrtc/signal")
    fun sendSignal(
        @RequestHeader("X-Session-Token") sessionToken: String,
        @RequestBody request: WebRtcSignalRequest,
    ): Mono<Void> {
        return sessionPrincipalResolver.requireUser(sessionToken)
            .flatMap { principal ->
                matchService.getMatchForUser(request.matchId, principal.id)
                    .flatMap { match ->
                        if (match.players.none { it.userId == request.targetUserId }) {
                            Mono.error(
                                DomainException(
                                    HttpStatus.NOT_FOUND,
                                    "Signal target is not part of the requested match.",
                                ),
                            )
                        } else {
                            realtimeService.publishSignal(
                                WebRtcSignalEvent(
                                    matchId = request.matchId,
                                    senderUserId = principal.id,
                                    senderDisplayName = principal.displayName,
                                    targetUserId = request.targetUserId,
                                    type = request.type,
                                    sdp = request.sdp,
                                    candidate = request.candidate,
                                    sdpMid = request.sdpMid,
                                    sdpMLineIndex = request.sdpMLineIndex,
                                    createdAt = Instant.now(),
                                ),
                            )
                        }
                    }
            }
    }
}

@Component
class MatchWebSocketHandler(
    private val sessionPrincipalResolver: SessionPrincipalResolver,
    private val matchService: MatchService,
    private val realtimeService: MatchRealtimeService,
    private val objectMapper: ObjectMapper,
) : WebSocketHandler {
    // Resync only — do NOT call processDueMatches here.
    // MatchLifecycleScheduler is the sole owner of due-match ticks; running them
    // per WebSocket connection scaled Mongo/Redis lock load with player count.
    private val snapshotSyncInterval = Duration.ofMillis(2000)

    override fun handle(session: WebSocketSession): Mono<Void> {
        val uri = session.handshakeInfo.uri
        val matchId = uri.path.substringAfterLast("/")
        val sessionToken = queryParam(uri, "sessionToken")
            ?: return session.close()

        return sessionPrincipalResolver.requireUser(sessionToken)
            .flatMap { principal ->
                matchService.getMatchForUser(matchId, principal.id)
                    .flatMap { match ->
                        val initialMessage = session.textMessage(
                            objectMapper.writeValueAsString(
                                MatchRealtimeEnvelope(
                                    type = "match_snapshot",
                                    match = match.toSnapshot(),
                                ),
                            ),
                        )

                        val outbound = Flux.concat(
                            Mono.just(initialMessage),
                            Flux.merge(
                                realtimeService.stream(matchId),
                                periodicSnapshotStream(matchId, principal.id),
                            ).map(session::textMessage),
                        )

                        session.send(outbound).and(session.receive().then())
                    }
            }
            .onErrorResume {
                session.close()
            }
    }

    private fun periodicSnapshotStream(matchId: String, userId: String): Flux<String> {
        return Flux.interval(snapshotSyncInterval)
            .concatMap {
                matchService.getMatchForUser(matchId, userId)
                    .map { match ->
                        objectMapper.writeValueAsString(
                            MatchRealtimeEnvelope(
                                type = "match_snapshot",
                                match = match.toSnapshot(),
                            ),
                        )
                    }
                    .onErrorResume { Mono.empty() }
            }
            .distinctUntilChanged()
    }

    private fun queryParam(uri: URI, name: String): String? {
        return uri.query
            ?.split("&")
            ?.mapNotNull { chunk ->
                val parts = chunk.split("=", limit = 2)
                if (parts.size == 2 && parts[0] == name) {
                    parts[1]
                } else {
                    null
                }
            }
            ?.firstOrNull()
    }
}

@Configuration
class RealtimeWebSocketConfig {
    @Bean
    fun matchSocketHandlerMapping(matchWebSocketHandler: MatchWebSocketHandler): SimpleUrlHandlerMapping {
        return SimpleUrlHandlerMapping(
            mapOf("/ws/matches/**" to matchWebSocketHandler),
            -1,
        )
    }

    @Bean
    fun webSocketHandlerAdapter(): WebSocketHandlerAdapter = WebSocketHandlerAdapter()
}
