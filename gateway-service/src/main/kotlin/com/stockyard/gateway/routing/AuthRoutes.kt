package com.stockyard.gateway.routing

import com.stockyard.gateway.auth.AuthService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * `/v1/auth/{register,login,refresh}` — реальный auth-flow (TASK-005).
 * Контракты см. docs/architecture/05-communication.md §5.3.2.
 */
fun Route.authRoutes(auth: AuthService) {
    route("/v1/auth") {
        post("/register") {
            val req = call.receive<RegisterRequest>()
            val result = auth.register(req.email, req.password)
            call.respond(
                HttpStatusCode.Created,
                RegisterResponse(
                    userId = result.userId,
                    accessToken = result.tokens.accessToken,
                    refreshToken = result.tokens.refreshToken,
                    expiresIn = result.tokens.expiresIn,
                ),
            )
        }
        post("/login") {
            val req = call.receive<LoginRequest>()
            val tokens = auth.login(req.email, req.password)
            call.respond(
                HttpStatusCode.OK,
                TokenPairResponse(
                    accessToken = tokens.accessToken,
                    refreshToken = tokens.refreshToken,
                    expiresIn = tokens.expiresIn,
                ),
            )
        }
        post("/refresh") {
            val req = call.receive<RefreshRequest>()
            val tokens = auth.refresh(req.refreshToken)
            call.respond(
                HttpStatusCode.OK,
                TokenPairResponse(
                    accessToken = tokens.accessToken,
                    refreshToken = tokens.refreshToken,
                    expiresIn = tokens.expiresIn,
                ),
            )
        }
    }
}
