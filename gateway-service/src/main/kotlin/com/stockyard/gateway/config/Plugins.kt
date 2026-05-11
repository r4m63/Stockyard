package com.stockyard.gateway.config

import com.stockyard.gateway.auth.JwtVerifiers
import com.stockyard.gateway.error.installErrorMapping
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.uri
import io.ktor.server.websocket.WebSockets
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

fun Application.installPlugins(verifiers: JwtVerifiers) {
    // StatusPages должен быть установлен ПЕРВЫМ, чтобы перехватить ошибки от
    // последующих плагинов (включая 401 от Authentication) и привести их
    // к единому формату {"error":{"code","message","details"}}.
    // См. /reviewer TASK-003 finding M2.
    installErrorMapping()

    install(ContentNegotiation) {
        json(Json {
            prettyPrint = false
            ignoreUnknownKeys = true
            encodeDefaults = false
        })
    }

    install(CallLogging) {
        level = Level.INFO
        // health и metrics — шумные и не информативные в логах
        filter { call ->
            val p = call.request.path()
            !p.startsWith("/health") && p != "/metrics"
        }
        // TASK-010 Q7: redact `?token=<jwt>` из логируемого URI, чтобы short-TTL
        // access-токен не попадал в access-логи и proxy-логи (ADR-014).
        // Покрывает WS-handshake на /v1/ws/quotes; для остальных путей no-op.
        format { call ->
            val status = call.response.status()?.value ?: 0
            val method = call.request.httpMethod.value
            val redactedUri = call.request.uri.replace(TOKEN_QUERY_REGEX, "$1token=REDACTED")
            "$status $method $redactedUri"
        }
    }

    install(CORS) {
        anyHost()
        allowHeader("Authorization")
        allowHeader("Content-Type")
        allowHeader("Idempotency-Key")
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Delete)
    }

    install(WebSockets) {
        pingPeriodMillis = 30_000
        timeoutMillis = 60_000
        maxFrameSize = 64L * 1024
    }

    install(Authentication) {
        // Конфигурация готова, но пока никем не используется (будет в TASK-005+).
        jwt("auth-jwt") {
            realm = "stockyard"
            verifier(verifiers.accessVerifier)
            validate { credential ->
                if (!credential.payload.subject.isNullOrEmpty()) JWTPrincipal(credential.payload) else null
            }
        }
    }
}

private val TOKEN_QUERY_REGEX: Regex = Regex("([?&])token=[^&]*")
