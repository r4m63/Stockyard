package com.stockyard.gateway.error

import com.stockyard.gateway.auth.EmailTakenException
import com.stockyard.gateway.auth.GatewayValidationException
import com.stockyard.gateway.auth.IdempotencyConflictException
import com.stockyard.gateway.auth.InsufficientFundsException
import com.stockyard.gateway.auth.InsufficientPositionException
import com.stockyard.gateway.auth.InvalidCredentialsException
import com.stockyard.gateway.auth.InvalidQuantityException
import com.stockyard.gateway.auth.InvalidRefreshTokenException
import com.stockyard.gateway.auth.InvalidTickerException
import com.stockyard.gateway.auth.MissingIdempotencyKeyException
import com.stockyard.gateway.auth.NoQuoteAvailableException
import com.stockyard.gateway.client.CoreServiceException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
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
        exception<GatewayValidationException> { call, cause ->
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
        exception<InvalidCredentialsException> { call, _ ->
            call.respond(
                HttpStatusCode.Unauthorized,
                ApiErrorBody(ApiError("INVALID_CREDENTIALS", "invalid email or password")),
            )
        }
        exception<InvalidRefreshTokenException> { call, _ ->
            call.respond(
                HttpStatusCode.Unauthorized,
                ApiErrorBody(ApiError("INVALID_REFRESH_TOKEN", "refresh token is invalid or expired")),
            )
        }
        exception<MissingIdempotencyKeyException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiErrorBody(ApiError("BAD_REQUEST", "Idempotency-Key header is required")),
            )
        }
        exception<IdempotencyConflictException> { call, _ ->
            call.respond(
                HttpStatusCode.Conflict,
                ApiErrorBody(ApiError("IDEMPOTENCY_CONFLICT", "idempotency key reused with different body")),
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
        exception<CoreServiceException> { call, cause ->
            log.error("Core service unavailable on {} {}: {}", call.request.httpMethod.value, call.request.path(), cause.message)
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ApiErrorBody(ApiError("STORAGE_UNAVAILABLE", "upstream service unavailable")),
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
        status(HttpStatusCode.Unauthorized) { call, _ ->
            call.respond(
                HttpStatusCode.Unauthorized,
                ApiErrorBody(ApiError("UNAUTHORIZED", "Authentication required")),
            )
        }
    }
}
