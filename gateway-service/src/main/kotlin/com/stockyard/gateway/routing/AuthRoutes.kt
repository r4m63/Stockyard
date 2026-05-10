package com.stockyard.gateway.routing

import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Stubs. Реальный flow реализуется в TASK-005, когда core-service публикует
 * `POST /internal/users` и `POST /internal/auth`.
 *
 * Контракты см. docs/architecture/05-communication.md §5.3.2.
 */
fun Route.authRoutes() {
    route("/v1/auth") {
        post("/register") { throw NotImplementedError("POST /v1/auth/register coming in TASK-005") }
        post("/login")    { throw NotImplementedError("POST /v1/auth/login coming in TASK-005") }
        post("/refresh")  { throw NotImplementedError("POST /v1/auth/refresh coming in TASK-005") }
    }
}
