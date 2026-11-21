package com.craft.ludo.provider

import com.craft.ludo.shared.api.DomainException
import com.craft.ludo.shared.config.AppProperties
import com.craft.ludo.shared.config.SessionServiceProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.io.IOException
import java.net.URI
import java.time.Duration

class ProviderSessionFailureTests {
    private fun client(exchange: ExchangeFunction) = ProviderGameSdkClient(
        WebClient.builder().exchangeFunction(exchange),
        AppProperties(sessionService = SessionServiceProperties(enabled = true)),
        jacksonObjectMapper(),
    )

    @Test
    fun `provider timeout returns gateway timeout instead of internal server error`() {
        StepVerifier.withVirtualTime {
            client(ExchangeFunction { Mono.never() }).validateSession("test-token")
        }
            .thenAwait(Duration.ofSeconds(5))
            .expectErrorSatisfies { error ->
                assertThat(error).isInstanceOf(DomainException::class.java)
                assertThat((error as DomainException).status).isEqualTo(HttpStatus.GATEWAY_TIMEOUT)
            }
            .verify()
    }

    @Test
    fun `provider connection failure returns bad gateway`() {
        val failure = WebClientRequestException(
            IOException("Connection refused"),
            HttpMethod.POST,
            URI.create("http://provider.invalid/sessions/validate"),
            HttpHeaders(),
        )
        StepVerifier.create(client(ExchangeFunction { Mono.error(failure) }).validateSession("test-token"))
            .expectErrorSatisfies { error ->
                assertThat(error).isInstanceOf(DomainException::class.java)
                assertThat((error as DomainException).status).isEqualTo(HttpStatus.BAD_GATEWAY)
            }
            .verify()
    }

    @Test
    fun `invalid provider session remains unauthorized`() {
        val exchange = ExchangeFunction {
            Mono.just(ClientResponse.create(HttpStatus.UNAUTHORIZED).build())
        }
        StepVerifier.create(client(exchange).validateSession("test-token"))
            .expectErrorSatisfies { error ->
                assertThat(error).isInstanceOf(DomainException::class.java)
                assertThat((error as DomainException).status).isEqualTo(HttpStatus.UNAUTHORIZED)
            }
            .verify()
    }
}
