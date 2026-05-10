package com.stockyard.gateway.error

import com.stockyard.gateway.auth.EmailTakenException
import com.stockyard.gateway.auth.GatewayValidationException
import com.stockyard.gateway.auth.InvalidCredentialsException
import com.stockyard.gateway.auth.InvalidRefreshTokenException
import com.stockyard.gateway.client.CoreServiceException
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
