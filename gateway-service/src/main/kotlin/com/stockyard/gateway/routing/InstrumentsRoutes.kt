package com.stockyard.gateway.routing

import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** Stub. Реальный flow — TASK-007. См. 05-communication.md §5.3.2 (Каталог инструментов). */
fun Route.instrumentsRoutes() {
    get("/v1/instruments") { throw NotImplementedError("GET /v1/instruments coming in TASK-007") }
}
