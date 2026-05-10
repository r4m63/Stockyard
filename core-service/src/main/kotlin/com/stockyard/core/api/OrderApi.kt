package com.stockyard.core.api

import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Internal Order API. Stubs only — реальный flow в TASK-006
 * (BUY/SELL TX по 07-consistency §7.2 с FOR UPDATE на accounts/positions).
 */
fun Route.orderApi() {
    route("/internal") {
        post("/orders") { throw NotImplementedError("POST /internal/orders coming in TASK-006") }
        get("/users/{userId}/orders") {
            throw NotImplementedError("GET /internal/users/{userId}/orders coming in TASK-006")
        }
    }
}
