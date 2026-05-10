package com.stockyard.core.api

import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * Internal Portfolio API. Stub — реальный flow в TASK-007.
 * Возвращает balance + positions[]; читает PG (accounts + positions).
 */
fun Route.portfolioApi() {
    route("/internal") {
        get("/users/{userId}/portfolio") {
            throw NotImplementedError("GET /internal/users/{userId}/portfolio coming in TASK-007")
        }
    }
}
