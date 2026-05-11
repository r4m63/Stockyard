package com.stockyard.gateway.client

import com.stockyard.gateway.config.CoreServiceConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * HTTP-клиент к Core Service.
 * - TASK-003: только [healthReady] для /health/ready.
 * - TASK-005: [createUser], [authenticate] для auth-flow.
 */
class CoreServiceClient(private val cfg: CoreServiceConfig) : AutoCloseable {

    private val jsonCfg = Json { ignoreUnknownKeys = true }

    private val http: HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(jsonCfg)
        }
        install(HttpTimeout) {
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

    /**
     * POST /internal/users — создание пользователя в core.
     * @return [CreateUserResult.Created] с userId, либо [CreateUserResult.EmailTaken] / [CreateUserResult.Validation].
     * Любой иной HTTP-код — [CoreServiceException] (мапится в 503 на уровне ErrorMapper).
     */
    suspend fun createUser(email: String, password: String): CreateUserResult {
        val resp = http.post("${cfg.baseUrl}/internal/users") {
            contentType(ContentType.Application.Json)
            setBody(InternalCreateUserRequest(email = email, password = password))
        }
        return when (resp.status.value) {
            201 -> CreateUserResult.Created(resp.body<InternalCreateUserResponse>().userId)
            409 -> CreateUserResult.EmailTaken
            422 -> {
                val err = runCatching { resp.body<InternalApiErrorEnvelope>().error }.getOrNull()
                CreateUserResult.Validation(err?.code ?: "INVALID_REQUEST", err?.message ?: "validation failed")
            }
            else -> throw CoreServiceException("createUser failed: HTTP ${resp.status.value}")
        }
    }

    /**
     * POST /internal/auth — проверка credentials в core.
     * Возвращает userId при успехе, null при invalid credentials.
     */
    suspend fun authenticate(email: String, password: String): String? {
        val resp = http.post("${cfg.baseUrl}/internal/auth") {
            contentType(ContentType.Application.Json)
            setBody(InternalAuthRequest(email = email, password = password))
        }
        if (resp.status.value != 200) {
            throw CoreServiceException("authenticate failed: HTTP ${resp.status.value}")
        }
        val body = resp.body<InternalAuthResponse>()
        return if (body.passwordValid) body.userId else null
    }

    /**
     * POST /internal/orders — размещение ордера в core.
     * Sealed [PlaceOrderResult]: бизнес-исходы (Created/Rejected.../InvalidTicker/.../Idempotency)
     * через типы, инфраструктурные ошибки (5xx/timeouts) — через [CoreServiceException].
     */
    suspend fun placeOrder(
        userId: String,
        ticker: String,
        side: String,
        qty: Int,
        idempotencyKey: String,
    ): PlaceOrderResult {
        val resp = http.post("${cfg.baseUrl}/internal/orders") {
            contentType(ContentType.Application.Json)
            setBody(
                InternalPlaceOrderRequest(
                    userId = userId, ticker = ticker, side = side, qty = qty,
                    idempotencyKey = idempotencyKey,
                ),
            )
        }
        return when (resp.status.value) {
            201 -> PlaceOrderResult.Created(resp.body<InternalOrderDto>())
            200 -> PlaceOrderResult.Created(resp.body<InternalOrderDto>())  // повтор идемпотентности
            409 -> {
                val code = readErrorCode(resp.body<JsonElement>())
                if (code == "IDEMPOTENCY_CONFLICT") PlaceOrderResult.IdempotencyConflict
                else throw CoreServiceException("placeOrder unexpected 409: $code")
            }
            422 -> {
                val body = resp.body<JsonElement>()
                val code = readErrorCode(body) ?: "INVALID_REQUEST"
                val details = (body.jsonObject["error"] as? JsonObject)?.get("details") as? JsonObject
                when (code) {
                    "INSUFFICIENT_FUNDS" -> PlaceOrderResult.InsufficientFunds(
                        requiredCents = details?.get("requiredCents")?.jsonPrimitive?.longOrNull ?: 0L,
                        availableCents = details?.get("availableCents")?.jsonPrimitive?.longOrNull ?: 0L,
                    )
                    "INSUFFICIENT_POSITION" -> PlaceOrderResult.InsufficientPosition(
                        requiredQty = details?.get("requiredQty")?.jsonPrimitive?.longOrNull?.toInt() ?: 0,
                        availableQty = details?.get("availableQty")?.jsonPrimitive?.longOrNull?.toInt() ?: 0,
                    )
                    "INVALID_TICKER" -> PlaceOrderResult.InvalidTicker(ticker)
                    "INVALID_QUANTITY" -> PlaceOrderResult.InvalidQuantity(qty)
                    "NO_QUOTE_AVAILABLE" -> PlaceOrderResult.NoQuoteAvailable(ticker)
                    else -> PlaceOrderResult.Validation(code, "validation failed")
                }
            }
            else -> throw CoreServiceException("placeOrder failed: HTTP ${resp.status.value}")
        }
    }

    /**
     * GET /internal/users/{userId}/orders — listing с keyset-пагинацией.
     */
    suspend fun listOrders(
        userId: String,
        statusFilter: String?,
        limit: Int,
        cursor: String?,
    ): InternalListOrdersResponse {
        val resp = http.get("${cfg.baseUrl}/internal/users/$userId/orders") {
            if (statusFilter != null) parameter("status", statusFilter)
            parameter("limit", limit.toString())
            if (cursor != null) parameter("cursor", cursor)
        }
        if (resp.status.value !in 200..299) {
            throw CoreServiceException("listOrders failed: HTTP ${resp.status.value}")
        }
        return resp.body()
    }

    // ---- TASK-007: read-side API ----

    /** GET /internal/users/{userId}/portfolio → balance + positions. */
    suspend fun getPortfolio(userId: String): InternalPortfolioDto {
        val resp = http.get("${cfg.baseUrl}/internal/users/$userId/portfolio")
        if (resp.status.value != 200) throw CoreServiceException("getPortfolio failed: HTTP ${resp.status.value}")
        return resp.body()
    }

    /**
     * GET /internal/quotes/{ticker} → текущая котировка.
     * Sealed [QuoteResult]: Found / NotFound (404) / Unavailable (422 NO_QUOTE_AVAILABLE).
     */
    suspend fun getQuote(ticker: String): QuoteResult {
        val resp = http.get("${cfg.baseUrl}/internal/quotes/$ticker")
        return when (resp.status.value) {
            200 -> QuoteResult.Found(resp.body<InternalQuoteDto>())
            404 -> QuoteResult.NotFound(ticker)
            422 -> {
                val code = readErrorCode(resp.body<JsonElement>())
                if (code == "NO_QUOTE_AVAILABLE") QuoteResult.Unavailable(ticker)
                else throw CoreServiceException("getQuote unexpected 422: $code")
            }
            else -> throw CoreServiceException("getQuote failed: HTTP ${resp.status.value}")
        }
    }

    /**
     * GET /internal/quotes/{ticker}/history → свечи. Sealed [HistoryResult].
     */
    suspend fun getQuoteHistory(
        ticker: String,
        from: String,
        to: String,
        interval: String,
    ): HistoryResult {
        val resp = http.get("${cfg.baseUrl}/internal/quotes/$ticker/history") {
            parameter("from", from)
            parameter("to", to)
            parameter("interval", interval)
        }
        return when (resp.status.value) {
            200 -> HistoryResult.Ok(resp.body<InternalCandlesDto>())
            404 -> HistoryResult.NotFound(ticker)
            422 -> {
                val code = readErrorCode(resp.body<JsonElement>())
                when (code) {
                    "INVALID_INTERVAL" -> HistoryResult.InvalidInterval(interval)
                    "INVALID_TIME_RANGE" -> HistoryResult.InvalidRange("from/to/interval")
                    else -> throw CoreServiceException("getQuoteHistory unexpected 422: $code")
                }
            }
            else -> throw CoreServiceException("getQuoteHistory failed: HTTP ${resp.status.value}")
        }
    }

    /** GET /internal/instruments → каталог. */
    suspend fun listInstruments(): InternalInstrumentsDto {
        val resp = http.get("${cfg.baseUrl}/internal/instruments")
        if (resp.status.value != 200) throw CoreServiceException("listInstruments failed: HTTP ${resp.status.value}")
        return resp.body()
    }

    private fun readErrorCode(body: JsonElement): String? =
        runCatching { body.jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content }.getOrNull()

    override fun close() = http.close()
}

/** Результат createUser — sealed type вместо exceptions для бизнес-исходов. */
sealed interface CreateUserResult {
    data class Created(val userId: String) : CreateUserResult
    data object EmailTaken : CreateUserResult
    data class Validation(val code: String, val message: String) : CreateUserResult
}

/** Результат placeOrder. */
sealed interface PlaceOrderResult {
    data class Created(val order: InternalOrderDto) : PlaceOrderResult
    data object IdempotencyConflict : PlaceOrderResult
    data class InsufficientFunds(val requiredCents: Long, val availableCents: Long) : PlaceOrderResult
    data class InsufficientPosition(val requiredQty: Int, val availableQty: Int) : PlaceOrderResult
    data class InvalidTicker(val ticker: String) : PlaceOrderResult
    data class InvalidQuantity(val qty: Int) : PlaceOrderResult
    data class NoQuoteAvailable(val ticker: String) : PlaceOrderResult
    data class Validation(val code: String, val message: String) : PlaceOrderResult
}

/** Бросается при неожиданном HTTP-статусе от core (5xx, network failure). */
class CoreServiceException(message: String) : RuntimeException(message)

@Serializable
private data class InternalCreateUserRequest(val email: String, val password: String)

@Serializable
private data class InternalCreateUserResponse(val userId: String)

@Serializable
private data class InternalAuthRequest(val email: String, val password: String)

@Serializable
private data class InternalAuthResponse(val userId: String? = null, val passwordValid: Boolean)

@Serializable
private data class InternalApiErrorEnvelope(val error: InternalApiError)

@Serializable
private data class InternalApiError(val code: String, val message: String)

@Serializable
private data class InternalPlaceOrderRequest(
    val userId: String,
    val ticker: String,
    val side: String,
    val qty: Int,
    val idempotencyKey: String,
)

@Serializable
data class InternalOrderDto(
    val orderId: String,
    val status: String,
    val ticker: String,
    val side: String,
    val qty: Int,
    val priceCents: Long? = null,
    val createdAt: String,
    val executedAt: String? = null,
)

@Serializable
data class InternalListOrdersResponse(
    val items: List<InternalOrderDto>,
    val nextCursor: String? = null,
)

// ---- TASK-007: read-side DTOs ----

@Serializable
data class InternalBalanceDto(val amountCents: Long, val currency: String)

@Serializable
data class InternalPositionDto(
    val ticker: String,
    val qty: Int,
    val avgPriceCents: Long,
    val currentPriceCents: Long? = null,
    val unrealizedPnlCents: Long? = null,
)

@Serializable
data class InternalPortfolioDto(
    val balance: InternalBalanceDto,
    val positions: List<InternalPositionDto>,
)

@Serializable
data class InternalQuoteDto(
    val ticker: String,
    val bidCents: Long,
    val askCents: Long,
    val lastCents: Long,
    val ts: String,
)

@Serializable
data class InternalCandleDto(
    val ts: String,
    val openCents: Long,
    val highCents: Long,
    val lowCents: Long,
    val closeCents: Long,
    val volume: Long,
)

@Serializable
data class InternalCandlesDto(
    val ticker: String,
    val interval: String,
    val candles: List<InternalCandleDto>,
)

@Serializable
data class InternalInstrumentItem(
    val ticker: String,
    val name: String,
    val type: String,
    val lotSize: Int,
)

@Serializable
data class InternalInstrumentsDto(val items: List<InternalInstrumentItem>)

sealed interface QuoteResult {
    data class Found(val quote: InternalQuoteDto) : QuoteResult
    data class NotFound(val ticker: String) : QuoteResult
    data class Unavailable(val ticker: String) : QuoteResult
}

sealed interface HistoryResult {
    data class Ok(val payload: InternalCandlesDto) : HistoryResult
    data class NotFound(val ticker: String) : HistoryResult
    data class InvalidInterval(val raw: String) : HistoryResult
    data class InvalidRange(val reason: String) : HistoryResult
}
