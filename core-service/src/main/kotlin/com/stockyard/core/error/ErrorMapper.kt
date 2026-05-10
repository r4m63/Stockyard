package com.stockyard.core.error

import com.stockyard.core.domain.order.IdempotencyConflictException
import com.stockyard.core.domain.order.InsufficientFundsException
import com.stockyard.core.domain.order.InsufficientPositionException
import com.stockyard.core.domain.order.InvalidQuantityException
import com.stockyard.core.domain.order.InvalidTickerException
import com.stockyard.core.domain.order.NoQuoteAvailableException
import com.stockyard.core.domain.quotes.InstrumentNotFoundException
import com.stockyard.core.domain.quotes.InvalidIntervalException
import com.stockyard.core.domain.quotes.InvalidTimeRangeException
import com.stockyard.core.domain.user.EmailTakenException
import com.stockyard.core.domain.user.ValidationException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory

fun Application.installErrorMapping() {
    val log = LoggerFactory.getLogger("ErrorMapper")
    install(StatusPages) {
        exception<NotImplementedError> { call, cause ->
            call.respond(
                HttpStatusCode.NotImplemented,
                ApiErrorBody(ApiError("NOT_IMPLEMENTED", cause.message ?: "Not implemented yet")),
            )
        }
        exception<ValidationException> { call, cause ->
            call.respond(
                HttpStatusCode.UnprocessableEntity,
                ApiErrorBody(ApiError(cause.errorCode, cause.message ?: "validation failed")),
            )
        }
        exception<EmailTakenException> { call, _ ->
            call.respond(
                HttpStatusCode.Conflict,
                ApiErrorBody(ApiError("EMAIL_TAKEN", "email already registered")),
            )
        }
        exception<InvalidTickerException> { call, cause ->
            call.respond(
                HttpStatusCode.UnprocessableEntity,
                ApiErrorBody(ApiError("INVALID_TICKER", cause.message ?: "invalid ticker")),
            )
        }
        exception<InvalidQuantityException> { call, cause ->
            call.respond(
                HttpStatusCode.UnprocessableEntity,
                ApiErrorBody(ApiError("INVALID_QUANTITY", cause.message ?: "invalid quantity")),
            )
        }
        exception<InsufficientFundsException> { call, cause ->
            call.respond(
                HttpStatusCode.UnprocessableEntity,
                ApiErrorBody(
                    ApiError(
                        "INSUFFICIENT_FUNDS",
                        "insufficient funds",
                        buildJsonObject {
                            put("requiredCents", JsonPrimitive(cause.requiredCents))
                            put("availableCents", JsonPrimitive(cause.availableCents))
                        },
                    ),
                ),
            )
        }
        exception<InsufficientPositionException> { call, cause ->
            call.respond(
                HttpStatusCode.UnprocessableEntity,
                ApiErrorBody(
                    ApiError(
                        "INSUFFICIENT_POSITION",
                        "insufficient position",
                        buildJsonObject {
                            put("requiredQty", JsonPrimitive(cause.requiredQty))
                            put("availableQty", JsonPrimitive(cause.availableQty))
                        },
                    ),
                ),
            )
        }
        exception<NoQuoteAvailableException> { call, cause ->
            call.respond(
                HttpStatusCode.UnprocessableEntity,
                ApiErrorBody(ApiError("NO_QUOTE_AVAILABLE", cause.message ?: "no quote")),
            )
        }
        exception<IdempotencyConflictException> { call, _ ->
            call.respond(
                HttpStatusCode.Conflict,
                ApiErrorBody(ApiError("IDEMPOTENCY_CONFLICT", "idempotency key reused with different body")),
            )
        }
        exception<InstrumentNotFoundException> { call, cause ->
            call.respond(
                HttpStatusCode.NotFound,
                ApiErrorBody(ApiError("INSTRUMENT_NOT_FOUND", cause.message ?: "instrument not found")),
            )
        }
        exception<InvalidIntervalException> { call, cause ->
            call.respond(
                HttpStatusCode.UnprocessableEntity,
                ApiErrorBody(ApiError("INVALID_INTERVAL", cause.message ?: "invalid interval")),
            )
        }
        exception<InvalidTimeRangeException> { call, cause ->
            call.respond(
                HttpStatusCode.UnprocessableEntity,
                ApiErrorBody(ApiError("INVALID_TIME_RANGE", cause.message ?: "invalid time range")),
            )
        }
        exception<BadRequestException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiErrorBody(ApiError("BAD_REQUEST", cause.message ?: "Bad request")),
            )
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiErrorBody(ApiError("BAD_REQUEST", cause.message ?: "Bad request")),
            )
        }
        exception<Throwable> { call, cause ->
            log.error("Unhandled exception in {} {}", call.request.httpMethod.value, call.request.path(), cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiErrorBody(ApiError("INTERNAL_ERROR", "Internal server error")),
            )
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respond(
                HttpStatusCode.NotFound,
                ApiErrorBody(ApiError("NOT_FOUND", "Resource not found")),
            )
        }
    }
}
