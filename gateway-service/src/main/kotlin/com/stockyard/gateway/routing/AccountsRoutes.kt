package com.stockyard.gateway.routing

import com.stockyard.gateway.auth.InvalidAmountException
import com.stockyard.gateway.auth.MissingIdempotencyKeyException
import com.stockyard.gateway.auth.userId
import com.stockyard.gateway.client.CoreServiceClient
import com.stockyard.gateway.client.DepositResult
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * `/v1/accounts/...` routes — депозит и (в будущем) вывод. TASK-014.
 *
 * Депозит требует `Idempotency-Key` (ADR-005 pattern).
 * Сумма — положительная BIGINT cents в RUB.
 */
fun Route.accountsRoutes(coreClient: CoreServiceClient) {
    authenticate("auth-jwt") {
        route("/v1/accounts") {
            post("/deposit") {
                val idempotencyKey = call.request.headers["Idempotency-Key"]?.takeIf { it.isNotBlank() }
                    ?: throw MissingIdempotencyKeyException()
                val req = call.receive<DepositRequest>()
                val userId = call.userId()

                val currency = req.currency.ifBlank { "RUB" }
                if (req.amountCents <= 0L) throw InvalidAmountException(req.amountCents)

                val result = coreClient.deposit(
                    userId = userId,
                    amountCents = req.amountCents,
                    currency = currency,
                    idempotencyKey = idempotencyKey,
                )
                when (result) {
                    is DepositResult.Ok -> call.respond(
                        HttpStatusCode.Created,
                        DepositResponse(
                            transactionId = result.transactionId,
                            balanceCents = result.balanceCents,
                            currency = result.currency,
                        ),
                    )
                    DepositResult.InvalidAmount -> throw InvalidAmountException(req.amountCents)
                    is DepositResult.Validation -> throw IllegalArgumentException("${result.code}: ${result.message}")
                }
            }
        }
    }
}

@Serializable
data class DepositRequest(
    val amountCents: Long,
    val currency: String = "RUB",
)

@Serializable
data class DepositResponse(
    val transactionId: Long,
    val balanceCents: Long,
    val currency: String,
)
