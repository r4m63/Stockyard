package com.stockyard.gateway.routing

import com.stockyard.gateway.auth.IdempotencyConflictException
import com.stockyard.gateway.auth.InsufficientFundsException
import com.stockyard.gateway.auth.InsufficientPositionException
import com.stockyard.gateway.auth.InvalidQuantityException
import com.stockyard.gateway.auth.InvalidTickerException
import com.stockyard.gateway.auth.MissingIdempotencyKeyException
import com.stockyard.gateway.auth.NoQuoteAvailableException
import com.stockyard.gateway.auth.userId
import com.stockyard.gateway.client.CoreServiceClient
import com.stockyard.gateway.client.PlaceOrderResult
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * `/v1/orders` — публичный API ордеров (TASK-006).
 * См. docs/architecture/05-communication.md §5.3.2 и TASK-006 §3.1.
 */
fun Route.ordersRoutes(coreClient: CoreServiceClient) {
    authenticate("auth-jwt") {
        route("/v1/orders") {
            post {
                val idempotencyKey = call.request.headers["Idempotency-Key"]?.takeIf { it.isNotBlank() }
                    ?: throw MissingIdempotencyKeyException()
                val req = call.receive<PlaceOrderRequest>()
                val userId = call.userId()

                val result = coreClient.placeOrder(
                    userId = userId,
                    ticker = req.ticker,
                    side = req.side,
                    qty = req.qty,
                    idempotencyKey = idempotencyKey,
                )

                when (result) {
                    is PlaceOrderResult.Created -> {
                        val o = result.order
                        call.respond(
                            HttpStatusCode.Created,
                            PlaceOrderResponse(
                                orderId = o.orderId, status = o.status, ticker = o.ticker,
                                side = o.side, qty = o.qty, priceCents = o.priceCents,
                                createdAt = o.createdAt, executedAt = o.executedAt,
                            ),
                        )
                    }
                    PlaceOrderResult.IdempotencyConflict -> throw IdempotencyConflictException()
                    is PlaceOrderResult.InsufficientFunds ->
                        throw InsufficientFundsException(result.requiredCents, result.availableCents)
                    is PlaceOrderResult.InsufficientPosition ->
                        throw InsufficientPositionException(result.requiredQty, result.availableQty)
                    is PlaceOrderResult.InvalidTicker -> throw InvalidTickerException(result.ticker)
                    is PlaceOrderResult.InvalidQuantity -> throw InvalidQuantityException(result.qty)
                    is PlaceOrderResult.NoQuoteAvailable -> throw NoQuoteAvailableException(result.ticker)
                    is PlaceOrderResult.Validation ->
                        throw IllegalArgumentException("${result.code}: ${result.message}")
                }
            }

            get {
                val userId = call.userId()
                val statusFilter = call.request.queryParameters["status"]?.takeIf { it.isNotBlank() }
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                val cursor = call.request.queryParameters["cursor"]?.takeIf { it.isNotBlank() }

                val page = coreClient.listOrders(userId, statusFilter, limit, cursor)
                call.respond(
                    HttpStatusCode.OK,
                    ListOrdersResponse(
                        items = page.items.map {
                            OrderItemDto(
                                orderId = it.orderId, status = it.status, ticker = it.ticker,
                                side = it.side, qty = it.qty, priceCents = it.priceCents,
                                createdAt = it.createdAt, executedAt = it.executedAt,
                            )
                        },
                        nextCursor = page.nextCursor,
                    ),
                )
            }
        }
    }
}
