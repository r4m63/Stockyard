package com.stockyard.core.api

import com.stockyard.core.domain.order.OrderService
import com.stockyard.core.domain.order.OrderStatus
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Internal Order API. Контракты см. docs/architecture/05-communication.md §5.4.2
 * и task ledger TASK-006 §3.2.
 *
 * Безопасность: userId — из body (gateway уже его извлёк из JWT). Доверяем gateway
 * в trusted-zone docker-сети. mTLS — 📦 backlog.
 */
fun Route.orderApi(orderService: OrderService) {
    route("/internal") {
        post("/orders") {
            val req = call.receive<InternalPlaceOrderRequest>()
            val side = parseSide(req.side)
            val order = orderService.place(
                userId = req.userId,
                ticker = req.ticker,
                side = side,
                qty = req.qty,
                idempotencyKey = req.idempotencyKey,
            )
            call.respond(HttpStatusCode.Created, order.toDto())
        }

        get("/users/{userId}/orders") {
            val userId = call.parameters["userId"] ?: throw IllegalArgumentException("missing userId")
            val statusFilter: OrderStatus? = parseStatusFilter(call.request.queryParameters["status"])
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT
            val cursor = call.request.queryParameters["cursor"]
            val page = orderService.listByUser(userId, statusFilter, limit, cursor)
            call.respond(
                HttpStatusCode.OK,
                InternalListOrdersResponse(
                    items = page.items.map { it.toDto() },
                    nextCursor = page.nextCursor,
                ),
            )
        }
    }
}

private const val DEFAULT_LIMIT = 50
