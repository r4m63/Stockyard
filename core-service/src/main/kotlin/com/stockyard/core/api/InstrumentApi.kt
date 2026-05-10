package com.stockyard.core.api

import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * Internal Instrument API. Stub — реальный flow в TASK-007.
 * SELECT ticker, name, lot_size FROM instruments — простой list.
 */
fun Route.instrumentApi() {
    route("/internal") {
        get("/instruments") {
            throw NotImplementedError("GET /internal/instruments coming in TASK-007")
        }
    }
}
