package com.stockyard.gateway.routing

import io.ktor.server.application.call

import com.stockyard.gateway.auth.InstrumentNotFoundException
import com.stockyard.gateway.auth.InvalidIntervalException
import com.stockyard.gateway.auth.InvalidTimeRangeException
import com.stockyard.gateway.auth.NoQuoteAvailableException
import com.stockyard.gateway.client.CoreServiceClient
import com.stockyard.gateway.client.HistoryResult
import com.stockyard.gateway.client.QuoteResult
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * `/v1/quotes/{ticker}` (current) + `/v1/quotes/{ticker}/history` (TASK-007).
 */
fun Route.quotesRoutes(coreClient: CoreServiceClient) {
    authenticate("auth-jwt") {
        route("/v1/quotes/{ticker}") {
            get {
                val ticker = call.parameters["ticker"]!!
                when (val res = coreClient.getQuote(ticker)) {
                    is QuoteResult.Found -> call.respond(
                        HttpStatusCode.OK,
                        QuoteResponse(
                            ticker = res.quote.ticker,
                            bidCents = res.quote.bidCents,
                            askCents = res.quote.askCents,
                            lastCents = res.quote.lastCents,
                            ts = res.quote.ts,
                        ),
                    )
                    is QuoteResult.NotFound -> throw InstrumentNotFoundException(res.ticker)
                    is QuoteResult.Unavailable -> throw NoQuoteAvailableException(res.ticker)
                }
            }

            get("/history") {
                val ticker = call.parameters["ticker"]!!
                val from = call.request.queryParameters["from"]
                    ?: throw IllegalArgumentException("missing query parameter: from")
                val to = call.request.queryParameters["to"]
                    ?: throw IllegalArgumentException("missing query parameter: to")
                val interval = call.request.queryParameters["interval"]
                    ?: throw IllegalArgumentException("missing query parameter: interval")

                when (val res = coreClient.getQuoteHistory(ticker, from, to, interval)) {
                    is HistoryResult.Ok -> {
                        val p = res.payload
                        call.respond(
                            HttpStatusCode.OK,
                            CandlesResponse(
                                ticker = p.ticker,
                                interval = p.interval,
                                candles = p.candles.map {
                                    CandleDto(
                                        ts = it.ts,
                                        openCents = it.openCents,
                                        highCents = it.highCents,
                                        lowCents = it.lowCents,
                                        closeCents = it.closeCents,
                                        volume = it.volume,
                                    )
                                },
                            ),
                        )
                    }
                    is HistoryResult.NotFound -> throw InstrumentNotFoundException(res.ticker)
                    is HistoryResult.InvalidInterval -> throw InvalidIntervalException(res.raw)
                    is HistoryResult.InvalidRange -> throw InvalidTimeRangeException(res.reason)
                }
            }
        }
    }
}
