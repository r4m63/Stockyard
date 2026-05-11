package com.stockyard.gateway.routing

import io.ktor.server.application.call

import com.stockyard.gateway.client.CoreServiceClient
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * `/v1/instruments` — каталог тикеров (TASK-007).
 */
fun Route.instrumentsRoutes(coreClient: CoreServiceClient) {
    authenticate("auth-jwt") {
        route("/v1/instruments") {
            get {
                val payload = coreClient.listInstruments()
                call.respond(
                    HttpStatusCode.OK,
                    InstrumentsResponse(
                        items = payload.items.map {
                            InstrumentDto(
                                ticker = it.ticker,
                                name = it.name,
                                type = it.type,
                                lotSize = it.lotSize,
                            )
                        },
                    ),
                )
            }
        }
    }
}
