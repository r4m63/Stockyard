package com.stockyard.core.api

import com.stockyard.core.domain.quotes.InvalidTimeRangeException
import com.stockyard.core.domain.quotes.QuotesService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Internal API котировок: current quote + history (1m/1h).
 */
fun Route.quotesApi(quotesService: QuotesService) {
    route("/internal") {
        get("/quotes/{ticker}") {
            val ticker = call.parameters["ticker"] ?: throw IllegalArgumentException("missing ticker")
            val quote = quotesService.getQuote(ticker)
            call.respond(HttpStatusCode.OK, quote.toDto(ticker))
        }

        get("/quotes/{ticker}/history") {
            val ticker = call.parameters["ticker"] ?: throw IllegalArgumentException("missing ticker")
            val from = parseInstantParam(call.request.queryParameters["from"], "from")
            val to = parseInstantParam(call.request.queryParameters["to"], "to")
            val interval = call.request.queryParameters["interval"]
                ?: throw IllegalArgumentException("missing interval")
            val result = quotesService.getHistory(ticker, from, to, interval)
            call.respond(HttpStatusCode.OK, result.toDto())
        }
    }
}

private fun parseInstantParam(raw: String?, name: String): Instant {
    if (raw.isNullOrBlank()) throw IllegalArgumentException("missing $name")
    return try {
        Instant.parse(raw)
    } catch (e: DateTimeParseException) {
        throw InvalidTimeRangeException("$name is not a valid ISO-8601 instant: $raw")
    }
}
