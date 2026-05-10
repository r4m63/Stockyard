package com.stockyard.core.api

import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Internal API for User domain. Stubs only — реальный flow в TASK-005.
 * Контракты см. docs/architecture/05-communication.md §5.4.2.
 */
fun Route.userApi() {
    route("/internal") {
        post("/users") { throw NotImplementedError("POST /internal/users coming in TASK-005") }
        post("/auth")  { throw NotImplementedError("POST /internal/auth coming in TASK-005") }
    }
}
