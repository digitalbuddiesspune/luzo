package com.craft.ludo.gameplay

import java.time.Instant

/** Only called for TOURNAMENT. Classic stops on its first winner as before. */
internal fun recordTournamentFinish(match: MatchDocument, userId: String, now: Instant, advanceDelayMillis: Long): MatchDocument {
    val order = (match.tournamentFinishingOrder + userId).distinct()
    return continueTournamentMatch(match.copy(tournamentFinishingOrder = order), now, advanceDelayMillis)
}

/** A disconnected player keeps their identity so reconnect and audit remain possible. */
internal fun recordTournamentForfeit(match: MatchDocument, now: Instant, advanceDelayMillis: Long): MatchDocument {
    val leaving = match.currentTurnUserId
    return continueTournamentMatch(match.copy(
        players = match.players.map { if (it.userId == leaving) it.copy(isAbandoned = true, finalTokens = it.tokens, tokens = listOf(-1, -1, -1, -1)) else it },
        events = (listOf(MatchEvent(actor = "System", detail = "${match.currentTurnDisplayName} forfeited after the tournament reconnect timeout.", createdAt = now)) + match.events).take(8),
    ), now, advanceDelayMillis)
}

private fun continueTournamentMatch(match: MatchDocument, now: Instant, delay: Long): MatchDocument {
    val remaining = match.players.filter { !it.isAbandoned && it.userId !in match.tournamentFinishingOrder }
    val finished = remaining.size <= 1
    val order = if (finished) match.tournamentFinishingOrder + remaining.map { it.userId } else match.tournamentFinishingOrder
    val nextIndex = (1..match.players.size).map { (match.currentPlayerIndex + it) % match.players.size }
        .firstOrNull { index -> match.players[index].userId in remaining.map { it.userId } } ?: match.currentPlayerIndex
    return match.copy(
        status = if (finished) MatchStatus.FINISHED else MatchStatus.ACTIVE,
        phase = if (finished) MatchPhase.FINISHED else MatchPhase.ADVANCING,
        tournamentFinishingOrder = order,
        winnerUserId = if (finished) order.firstOrNull() else null,
        winnerDisplayName = if (finished) match.players.find { it.userId == order.firstOrNull() }?.displayName else null,
        winnerReason = if (finished) "TOURNAMENT_RANKING" else null,
        selectableTokenIndexes = emptyList(),
        pendingNextPlayerIndex = if (finished) null else nextIndex,
        phaseDeadlineAt = if (finished) null else now.plusMillis(delay),
        turnDeadlineAt = null,
        updatedAt = now,
        sequence = match.sequence + 1,
    )
}
