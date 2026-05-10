package com.stockyard.core.api

import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * Internal Quotes API. Stub — реальный flow в TASK-008.
 * Читает свечи из ClickHouse через MV quotes_candles_1m / _1h.
 */
fun Route.quotesApi() {
    route("/internal") {
        get("/quotes/{ticker}/history") {
            throw NotImplementedError("GET /internal/quotes/{ticker}/history coming in TASK-008")
        }
    }
}
