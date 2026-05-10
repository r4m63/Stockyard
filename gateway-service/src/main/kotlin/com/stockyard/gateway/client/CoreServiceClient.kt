package com.stockyard.gateway.client

import com.stockyard.gateway.config.CoreServiceConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * HTTP-клиент к Core Service. В TASK-003 — единственный метод [healthReady]
 * для проверки upstream-health. Реальные методы (login, orders, portfolio)
 * добавляются в TASK-005..007 по мере того, как core-service публикует
 * соответствующие internal-эндпоинты.
 */
class CoreServiceClient(private val cfg: CoreServiceConfig) : AutoCloseable {

    private val http: HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            // Connect-timeout короткий, чтобы /health/ready не висел при недоступном
            // core-service. Request/socket — длиннее, под бизнес-вызовы из TASK-005+.
            connectTimeoutMillis = cfg.connectTimeoutMs
            requestTimeoutMillis = cfg.requestTimeoutMs
            socketTimeoutMillis = cfg.requestTimeoutMs
        }
        expectSuccess = false
    }

    suspend fun healthReady(): Boolean = runCatching {
        val resp: HttpResponse = http.get("${cfg.baseUrl}/health/ready")
        resp.status.value in 200..299
    }.getOrElse { false }

    override fun close() = http.close()
}
