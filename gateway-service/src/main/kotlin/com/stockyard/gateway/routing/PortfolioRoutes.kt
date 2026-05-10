package com.stockyard.gateway.routing

import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** Stub. Реальный flow — TASK-007. См. 05-communication.md §5.3.2 (Портфель). */
fun Route.portfolioRoutes() {
    get("/v1/portfolio") { throw NotImplementedError("GET /v1/portfolio coming in TASK-007") }
}
