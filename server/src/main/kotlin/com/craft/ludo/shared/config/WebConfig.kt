package com.craft.ludo.shared.config

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.http.HttpMethod
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.reactive.config.CorsRegistry
import org.springframework.web.reactive.config.WebFluxConfigurer

@Configuration
class WebConfig(
    appProperties: AppProperties,
    private val environment: Environment,
) : WebFluxConfigurer {
    private val allowedOriginPatterns = appProperties.web.allowedOriginPatterns
    private val corsConfiguration = CorsConfiguration().apply {
        allowedOriginPatterns = this@WebConfig.allowedOriginPatterns
    }

    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/api/**")
            .allowedOriginPatterns(*allowedOriginPatterns.toTypedArray())
            .allowedMethods("*")
            .allowedHeaders("*")
            .exposedHeaders("X-Session-Token")
            .maxAge(3600)
            .allowCredentials(false)

        registry.addMapping("/ws/**")
            .allowedOriginPatterns(*allowedOriginPatterns.toTypedArray())
            .allowedMethods(HttpMethod.GET.name())
            .allowedHeaders("*")
            .maxAge(3600)
            .allowCredentials(false)
    }

    @Bean
    fun corsDiagnosticsStartupLogger() = ApplicationRunner {
        log.info(
            "Environment diagnostics: profiles={}, port={}, app.web.allowed-origin-patterns property={}, APP_WEB_ALLOWED_ORIGIN_PATTERNS env={}, boundAllowedOriginPatterns={}",
            environment.activeProfiles.toList().ifEmpty { listOf("default") },
            environment.getProperty("server.port"),
            environment.getProperty("app.web.allowed-origin-patterns"),
            System.getenv("APP_WEB_ALLOWED_ORIGIN_PATTERNS"),
            allowedOriginPatterns,
        )
        log.info(
            "Environment diagnostics: MONGODB_DATABASE={}, REDIS_HOST={}, REDIS_PORT={}, REDIS_SSL_ENABLED={}, APP_OPERATOR_BASE_URL={}, APP_OPERATOR_GAME_ID={}, MONGODB_URI_present={}, REDIS_PASSWORD_present={}, APP_REALTIME_WEB_RTC_TURN_CREDENTIAL_present={}",
            System.getenv("MONGODB_DATABASE"),
            System.getenv("REDIS_HOST"),
            System.getenv("REDIS_PORT"),
            System.getenv("REDIS_SSL_ENABLED"),
            System.getenv("APP_OPERATOR_BASE_URL"),
            System.getenv("APP_OPERATOR_GAME_ID"),
            System.getenv("MONGODB_URI").isNullOrBlank().not(),
            System.getenv("REDIS_PASSWORD").isNullOrBlank().not(),
            System.getenv("APP_REALTIME_WEB_RTC_TURN_CREDENTIAL").isNullOrBlank().not(),
        )
    }

    @Bean
    fun corsDiagnosticsFilter() = WebFilter { exchange, chain ->
        val path = exchange.request.path.pathWithinApplication().value()
        val origin = exchange.request.headers.origin

        if (origin != null && path.isCorsMappedPath()) {
            val matchedOrigin = corsConfiguration.checkOrigin(origin)
            log.info(
                "CORS diagnostics: method={} path={} origin={} allowed={} matchedOrigin={} allowedOriginPatterns={}",
                exchange.request.method,
                path,
                origin,
                matchedOrigin != null,
                matchedOrigin,
                allowedOriginPatterns,
            )
        } else if (origin == null && path.isCorsMappedPath()) {
            log.debug(
                "CORS diagnostics: method={} path={} has no Origin header",
                exchange.request.method,
                path,
            )
        }

        chain.filter(exchange).doFinally {
            logCorsResponse(exchange)
        }
    }

    private fun logCorsResponse(exchange: ServerWebExchange) {
        val path = exchange.request.path.pathWithinApplication().value()
        val origin = exchange.request.headers.origin ?: return

        if (!path.isCorsMappedPath()) {
            return
        }

        log.info(
            "CORS diagnostics response: method={} path={} origin={} status={} accessControlAllowOrigin={}",
            exchange.request.method,
            path,
            origin,
            exchange.response.statusCode,
            exchange.response.headers.accessControlAllowOrigin,
        )
    }

    private fun String.isCorsMappedPath() =
        this == "/api" || startsWith("/api/") || this == "/ws" || startsWith("/ws/")

    companion object {
        private val log = LoggerFactory.getLogger(WebConfig::class.java)
    }
}
