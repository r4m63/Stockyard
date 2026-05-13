package com.stockyard.core.api

import com.stockyard.core.domain.portfolio.PortfolioService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * Internal API портфеля. Реализован в TASK-007.
 */
fun Route.portfolioApi(portfolioService: PortfolioService) {
    route("/internal") {
        get("/users/{userId}/portfolio") {
            val userId = call.parameters["userId"] ?: throw IllegalArgumentException("missing userId")
            val portfolio = portfolioService.getPortfolio(userId)
            call.respond(HttpStatusCode.OK, portfolio.toDto())
        }
    }
}
