package com.craft.ludo.tournament

import com.craft.ludo.identity.SessionPrincipalResolver
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketSession
import org.springframework.web.util.UriComponentsBuilder
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

@Component
class TournamentWebSocketHandler(private val service: TournamentService, private val identity: SessionPrincipalResolver, private val mapper: ObjectMapper) : WebSocketHandler {
    override fun handle(session: WebSocketSession): Mono<Void> {
        val uri = session.handshakeInfo.uri
        val token = UriComponentsBuilder.fromUri(uri).build().queryParams.getFirst("sessionToken") ?: return session.close()
        val id = uri.path.substringAfterLast('/')
        // Persisted state is the source of truth: each instance and reconnected client
        // receives the same snapshot, even if a process died between notifications.
        val seenEvents = mutableSetOf<String>()
        val snapshots = Flux.interval(Duration.ZERO, Duration.ofSeconds(2)).concatMap {
            identity.requireUser(token).then(service.get(id)).filter { it.status != TournamentStatus.DRAFT }
        }.distinctUntilChanged { it.version }.flatMapIterable { t ->
            val fresh = t.events.filter { it.id !in seenEvents }
            seenEvents.clear()
            seenEvents.addAll(t.events.map { it.id })
            listOf(session.textMessage(mapper.writeValueAsString(mapOf("type" to "TOURNAMENT_UPDATED", "tournament" to tournamentView(t))))) +
                fresh.map { e -> session.textMessage(mapper.writeValueAsString(mapOf("type" to e.action, "eventId" to e.id, "tournamentId" to t.id, "message" to e.reason, "version" to t.version))) }
        }
        return session.send(snapshots).and(session.receive().then()).onErrorResume { session.close() }
    }
}

@Configuration
class TournamentWebSocketConfiguration {
    @Bean fun tournamentSocketMapping(handler: TournamentWebSocketHandler) = SimpleUrlHandlerMapping(mapOf("/ws/tournaments/**" to handler), -2)
}
