package com.stockyard.gateway.routing

import com.stockyard.gateway.auth.userId
import com.stockyard.gateway.client.CoreServiceClient
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * `/v1/portfolio` — баланс + позиции + current price из Redis (TASK-007).
 */
fun Route.portfolioRoutes(coreClient: CoreServiceClient) {
    authenticate("auth-jwt") {
        route("/v1/portfolio") {
            get {
                val userId = call.userId()
                val payload = coreClient.getPortfolio(userId)
                call.respond(
                    HttpStatusCode.OK,
                    PortfolioResponse(
                        balance = BalanceDto(payload.balance.amountCents, payload.balance.currency),
                        positions = payload.positions.map {
                            PositionDto(
                                ticker = it.ticker,
                                qty = it.qty,
                                avgPriceCents = it.avgPriceCents,
                                currentPriceCents = it.currentPriceCents,
                                unrealizedPnlCents = it.unrealizedPnlCents,
                            )
                        },
                    ),
                )
            }
        }
    }
}
