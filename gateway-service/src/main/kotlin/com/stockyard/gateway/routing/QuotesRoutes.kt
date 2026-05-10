package com.stockyard.gateway.routing

import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * Stubs. Реальный flow — TASK-008 (live + history через Redis HGET и
 * ClickHouse SELECT через core-service).
 *
 * Контракты см. docs/architecture/05-communication.md §5.3.2 (Котировки).
 */
fun Route.quotesRoutes() {
    route("/v1/quotes/{ticker}") {
        get             { throw NotImplementedError("GET /v1/quotes/{ticker} coming in TASK-008") }
        get("/history") { throw NotImplementedError("GET /v1/quotes/{ticker}/history coming in TASK-008") }
    }
}
