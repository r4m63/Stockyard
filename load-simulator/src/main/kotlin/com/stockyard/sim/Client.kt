package com.stockyard.sim

import io.azam.ulidj.ULID
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Тонкая обёртка над gateway REST + WS для одного виртуального юзера.
 * Все ошибки фиксируются как метрики, не валит весь сценарий.
 */
class SimClient(private val baseUrl: String) : AutoCloseable {

    val http: HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(WebSockets)
        install(HttpTimeout) {
            connectTimeoutMillis = 3_000
            requestTimeoutMillis = 10_000
            socketTimeoutMillis = 10_000
        }
        expectSuccess = false
    }

    var accessToken: String? = null
        private set
    var refreshToken: String? = null
        private set
    var userId: String? = null
        private set

    suspend fun register(email: String, password: String): Boolean {
        val resp = http.post("$baseUrl/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(AuthRequest(email, password))
        }
        return if (resp.status.value in 200..299) {
            val body = resp.body<RegisterResponse>()
            accessToken = body.accessToken
            refreshToken = body.refreshToken
            userId = body.userId
            true
        } else false
    }

    suspend fun login(email: String, password: String): Boolean {
        val resp = http.post("$baseUrl/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(AuthRequest(email, password))
        }
        return if (resp.status.value in 200..299) {
            val body = resp.body<LoginResponse>()
            accessToken = body.accessToken
            refreshToken = body.refreshToken
            true
        } else false
    }

    suspend fun placeOrder(ticker: String, side: String, qty: Int): Int {
        val token = accessToken ?: return 401
        val resp = http.post("$baseUrl/v1/orders") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("Idempotency-Key", ULID.random())
            setBody(OrderRequest(ticker, side, qty))
        }
        return resp.status.value
    }

    suspend fun deposit(amountCents: Long): Int {
        val token = accessToken ?: return 401
        val resp = http.post("$baseUrl/v1/accounts/deposit") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("Idempotency-Key", ULID.random())
            setBody(DepositRequest(amountCents, "RUB"))
        }
        return resp.status.value
    }

    suspend fun portfolio(): Int {
        val token = accessToken ?: return 401
        val resp = http.get("$baseUrl/v1/portfolio") {
            header("Authorization", "Bearer $token")
        }
        return resp.status.value
    }

    /**
     * Запускает WS-соединение в отдельной корутине, подписывается на `tickers`.
     * Считает входящие quote-фреймы. Возвращает Job — кэнсель его, чтобы остановить WS.
     */
    fun startWs(scope: CoroutineScope, tickers: List<String>): Job {
        val token = accessToken ?: return Job().also { it.cancel() }
        val wsUrl = baseUrl.replaceFirst("http", "ws") + "/v1/ws/quotes?token=$token"
        return scope.launch(Dispatchers.IO) {
            try {
                http.webSocket(wsUrl) {
                    val sub = """{"action":"subscribe","tickers":${tickers.joinToString(",", "[", "]") { "\"$it\"" }}}"""
                    send(Frame.Text(sub))
                    while (isActive) {
                        val frame = incoming.receive()
                        if (frame is Frame.Text) {
                            val txt = frame.readText()
                            if (txt.contains("\"type\":\"quote\"")) Metrics.inc("ws.quote")
                            else if (txt.contains("\"type\":\"error\"")) Metrics.inc("ws.error")
                        }
                    }
                }
            } catch (_: Exception) {
                Metrics.inc("ws.disconnect")
            }
        }
    }

    override fun close() = http.close()
}

@Serializable
private data class AuthRequest(val email: String, val password: String)

@Serializable
private data class RegisterResponse(
    val userId: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long? = null,
)

@Serializable
private data class LoginResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long? = null,
)

@Serializable
private data class OrderRequest(val ticker: String, val side: String, val qty: Int)

@Serializable
private data class DepositRequest(val amountCents: Long, val currency: String)
