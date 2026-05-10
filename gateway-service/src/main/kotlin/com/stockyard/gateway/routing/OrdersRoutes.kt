package com.stockyard.gateway.routing

import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Stubs. Реальный flow — TASK-006.
 * Контракты см. docs/architecture/05-communication.md §5.3.2 (Ордера).
 */
fun Route.ordersRoutes() {
    route("/v1/orders") {
        post { throw NotImplementedError("POST /v1/orders coming in TASK-006") }
        get  { throw NotImplementedError("GET /v1/orders coming in TASK-006") }
    }
}
