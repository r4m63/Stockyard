package com.stockyard.core.api

import com.stockyard.core.domain.user.UserService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * Internal API для User-домена. Контракты см. docs/architecture/05-communication.md §5.4.2.
 *
 * **Plaintext password в internal API** — сознательное отклонение от примера в §5.4.2
 * (где было `passwordHash`). `PasswordHasher` централизован в core; pepper лежит только
 * в core — gateway не должен знать про pepper. Подробности — TASK-005 design ledger.
 */
fun Route.userApi(userService: UserService) {
    route("/internal") {
        post("/users") {
            val req = call.receive<InternalCreateUserRequest>()
            val userId = userService.register(req.email, req.password)
            call.respond(HttpStatusCode.Created, InternalCreateUserResponse(userId = userId))
        }
        post("/auth") {
            val req = call.receive<InternalAuthRequest>()
            val userId = userService.authenticate(req.email, req.password)
            call.respond(
                HttpStatusCode.OK,
                InternalAuthResponse(userId = userId, passwordValid = userId != null),
            )
        }
    }
}

@Serializable
data class InternalCreateUserRequest(val email: String, val password: String)

@Serializable
data class InternalCreateUserResponse(val userId: String)

@Serializable
data class InternalAuthRequest(val email: String, val password: String)

@Serializable
data class InternalAuthResponse(val userId: String?, val passwordValid: Boolean)
