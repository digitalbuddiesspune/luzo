package com.craft.ludo.tournament

import org.springframework.stereotype.Service

/** Pure deterministic planner: byes retain their source, never eliminate a player. */
@Service
class TournamentBracketService {
    data class PlannedRound(val number: Int, val players: Int, val qualifiers: List<Int>, val byes: Int) {
        val advancing: Int get() = qualifiers.sum() + byes
    }

    fun preview(capacity: Int, format: Int, rules: List<Int> = emptyList()): List<PlannedRound> {
        require(format == 2 || format == 4) { "Game format must be 2 or 4." }
        require(capacity in format..TournamentPolicy.MAX_CAPACITY) { "Capacity must be between $format and ${TournamentPolicy.MAX_CAPACITY}." }
        require(rules.all { it in 1 until format }) { "Qualifiers must be between 1 and ${format - 1}." }
        val result = mutableListOf<PlannedRound>()
        var players = capacity
        while (players > 1) {
            require(players >= format) { "Qualification rules leave fewer than $format players for the next round." }
            val matches = players / format
            val byes = players % format
            val final = players == format
            val configured = rules.getOrNull(result.size)
            require(!final || configured == null || configured == 1) { "The final must produce one champion." }
            val counts = when {
                final -> List(1) { 1 }
                configured != null -> List(matches) { configured }
                format == 2 -> List(matches) { 1 }
                else -> {
                    val minimum = matches + byes
                    val target = ((minimum + format - 1) / format) * format
                    val toQualify = target - byes
                    List(matches) { index -> toQualify / matches + if (index < toQualify % matches) 1 else 0 }
                }
            }
            val round = PlannedRound(result.size + 1, players, counts, byes)
            require(counts.all { it in 1..format } && round.advancing < players) { "Qualification rules must reduce the field each round." }
            require(round.advancing == 1 || round.advancing >= format) { "Qualification rules cannot fill the next round; use automatic qualifiers." }
            result.add(round)
            players = round.advancing
        }
        require(rules.size <= result.size) { "Too many qualification rules for this capacity." }
        return result
    }

    fun generate(tournament: TournamentDocument, orderedUserIds: List<String>): TournamentDocument {
        require(tournament.rounds.isEmpty()) { "Bracket has already been generated." }
        require(orderedUserIds.distinct().size == orderedUserIds.size) { "Duplicate bracket participants." }
        val plan = preview(orderedUserIds.size, tournament.config.gameFormat, tournament.config.qualifiersPerRound)
        var sources = orderedUserIds.map { "user:$it" }
        val rounds = mutableListOf<TournamentRound>()
        val matches = mutableListOf<TournamentMatch>()
        for (stage in plan) {
            val next = mutableListOf<String>()
            val roundMatches = stage.qualifiers.mapIndexed { index, qualifiers ->
                val id = "${tournament.id}:r${stage.number}:m${index + 1}"
                repeat(qualifiers) { rank -> next.add("match:$id:${rank + 1}") }
                TournamentMatch(id, tournament.id, stage.number, index + 1, tournament.config.gameFormat,
                    sources.drop(index * tournament.config.gameFormat).take(tournament.config.gameFormat), qualifiers)
            }
            val byes = sources.takeLast(stage.byes)
            next.addAll(byes)
            rounds.add(TournamentRound("${tournament.id}:r${stage.number}", tournament.id, stage.number,
                matchIds = roundMatches.map { it.id }, byeSources = byes))
            matches.addAll(roundMatches)
            sources = next
        }
        return tournament.copy(rounds = rounds, matches = matches, status = TournamentStatus.BRACKET_GENERATED)
    }

    fun resolve(source: String, matches: List<TournamentMatch>): String? {
        if (source.startsWith("user:")) return source.removePrefix("user:")
        val matchId = source.removePrefix("match:").substringBeforeLast(":")
        val rank = source.substringAfterLast(":").toIntOrNull() ?: return null
        return matches.find { it.id == matchId && it.resultVerified }?.qualifiedPlayerIds?.getOrNull(rank - 1)
    }
}
