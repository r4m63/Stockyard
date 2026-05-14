package com.stockyard.core.api

import com.stockyard.core.domain.quotes.QuotesService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * Internal API каталога инструментов. 50 тикеров MOEX сидируются V2.
 */
fun Route.instrumentApi(quotesService: QuotesService) {
    route("/internal") {
        get("/instruments") {
            val items = quotesService.listInstruments()
            call.respond(
                HttpStatusCode.OK,
                InternalInstrumentsResponse(items = items.map { it.toDto() }),
            )
        }
    }
}
