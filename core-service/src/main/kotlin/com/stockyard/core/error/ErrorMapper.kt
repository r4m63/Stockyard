package com.stockyard.core.error

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
