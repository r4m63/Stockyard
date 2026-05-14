package com.stockyard.core.api

import com.stockyard.core.domain.transaction.TransactionsService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * Internal API для DEPOSIT и истории транзакций (TASK-014).
 * Контракт ↔ Gateway: `POST /internal/accounts/{userId}/deposit`, `GET /internal/users/{userId}/transactions`.
 *
 * userId извлекается из URL — gateway уже валидировал JWT.
 */
fun Route.transactionsApi(service: TransactionsService) {
    route("/internal") {
        post("/accounts/{userId}/deposit") {
            val userId = call.parameters["userId"] ?: throw IllegalArgumentException("missing userId")
            val req = call.receive<InternalDepositRequest>()
            val result = service.deposit(
                userId = userId,
                amountCents = req.amountCents,
                currency = req.currency,
                idempotencyKey = req.idempotencyKey,
            )
            call.respond(
                HttpStatusCode.Created,
                InternalDepositResponse(
                    transactionId = result.transactionId,
                    balanceCents = result.balanceCents,
                    currency = result.currency,
                    replay = result.replay,
                ),
            )
        }

        get("/users/{userId}/transactions") {
            val userId = call.parameters["userId"] ?: throw IllegalArgumentException("missing userId")
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT
            val cursor = call.request.queryParameters["cursor"]
            val page = service.listByUser(userId, limit, cursor)
            call.respond(
                HttpStatusCode.OK,
                InternalListTransactionsResponse(
                    items = page.items.map { rec ->
                        InternalTransactionDto(
                            transactionId = rec.id,
                            type = rec.type.name,
                            amountCents = rec.amountCents,
                            refOrderId = rec.refOrderId,
                            createdAt = rec.createdAt.toString(),
                        )
                    },
                    nextCursor = page.nextCursor,
                ),
            )
        }
    }
}

private const val DEFAULT_LIMIT = 50

@Serializable
data class InternalDepositRequest(
    val amountCents: Long,
    val currency: String,
    val idempotencyKey: String,
)

@Serializable
data class InternalDepositResponse(
    val transactionId: Long,
    val balanceCents: Long,
    val currency: String,
    val replay: Boolean,
)

@Serializable
data class InternalListTransactionsResponse(
    val items: List<InternalTransactionDto>,
    val nextCursor: String?,
)

@Serializable
data class InternalTransactionDto(
    val transactionId: Long,
    val type: String,
    val amountCents: Long,
    val refOrderId: String?,
    val createdAt: String,
)
