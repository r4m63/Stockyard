package com.stockyard.gateway.routing

import com.stockyard.gateway.auth.userId
import com.stockyard.gateway.client.CoreServiceClient
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * `/v1/transactions` — история денежных движений пользователя (TASK-014).
 * Курсорная пагинация на уровне core.
 */
fun Route.transactionsRoutes(coreClient: CoreServiceClient) {
    authenticate("auth-jwt") {
        route("/v1/transactions") {
            get {
                val userId = call.userId()
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                val cursor = call.request.queryParameters["cursor"]?.takeIf { it.isNotBlank() }
                val page = coreClient.listTransactions(userId, limit, cursor)
                call.respond(
                    HttpStatusCode.OK,
                    ListTransactionsResponse(
                        items = page.items.map {
                            TransactionItem(
                                transactionId = it.transactionId,
                                type = it.type,
                                amountCents = it.amountCents,
                                refOrderId = it.refOrderId,
                                createdAt = it.createdAt,
                            )
                        },
                        nextCursor = page.nextCursor,
                    ),
                )
            }
        }
    }
}

@Serializable
data class TransactionItem(
    val transactionId: Long,
    val type: String,
    val amountCents: Long,
    val refOrderId: String?,
    val createdAt: String,
)

@Serializable
data class ListTransactionsResponse(
    val items: List<TransactionItem>,
    val nextCursor: String?,
)
