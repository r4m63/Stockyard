package com.stockyard.core.domain.transaction

import com.stockyard.core.domain.account.AccountRepository
import com.stockyard.core.persistence.TransactionManager
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.time.Instant
import java.util.Base64

/**
 * Сервис для DEPOSIT-операций и пагинированной выдачи истории транзакций (TASK-014).
 *
 * BUY/SELL audit пишет [com.stockyard.core.domain.order.OrderService] внутри своей TX —
 * этот сервис их не трогает, только читает (listByUser).
 *
 * Идемпотентность DEPOSIT: partial UNIQUE (user_id, type, idempotency_key) WHERE key NOT NULL,
 * см. V8 миграция + ADR-011.
 */
class TransactionsService(
    private val tx: TransactionManager,
    private val accounts: AccountRepository,
    private val transactions: TransactionRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Депозит на счёт пользователя. Атомарно:
     *   1) check idempotency replay → return existing,
     *   2) lock account FOR UPDATE,
     *   3) apply delta + insert audit row,
     *   4) commit.
     */
    suspend fun deposit(
        userId: String,
        amountCents: Long,
        currency: String,
        idempotencyKey: String,
    ): DepositResult {
        require(amountCents > 0) { "amountCents must be positive" }
        require(idempotencyKey.isNotBlank()) { "idempotency key must be non-empty" }
        require(currency == "RUB") { "only RUB supported in MVP" }

        return tx.withTx { conn ->
            // Idempotent replay: same key already inserted → return current balance + that txn id.
            transactions.findByIdempotency(conn, userId, TxnType.DEPOSIT, idempotencyKey)?.let { prev ->
                val balance = accounts.findBalance(conn, userId, currency)
                    ?: error("account missing for user=$userId currency=$currency")
                return@withTx DepositResult(
                    transactionId = prev.id,
                    balanceCents = balance,
                    currency = currency,
                    replay = true,
                )
            }

            val balance = accounts.findBalanceForUpdate(conn, userId, currency)
                ?: error("account missing for user=$userId currency=$currency")
            accounts.applyDelta(conn, userId, currency, amountCents)
            val txnId = try {
                transactions.insertWithIdempotency(
                    conn = conn,
                    userId = userId,
                    type = TxnType.DEPOSIT,
                    amountCents = amountCents,
                    idempotencyKey = idempotencyKey,
                )
            } catch (e: SQLException) {
                if (e.sqlState == SQLSTATE_UNIQUE_VIOLATION) {
                    // Race: concurrent deposit with same key won, we lost. Re-read and replay.
                    val existing = transactions.findByIdempotency(conn, userId, TxnType.DEPOSIT, idempotencyKey)
                        ?: throw IllegalStateException("UNIQUE violation but no row found")
                    return@withTx DepositResult(
                        transactionId = existing.id,
                        balanceCents = balance + amountCents,
                        currency = currency,
                        replay = true,
                    )
                }
                throw e
            }
            log.atInfo()
                .addKeyValue("user.id", userId)
                .addKeyValue("txn.id", txnId)
                .addKeyValue("amount.cents", amountCents)
                .log("deposit committed")
            DepositResult(
                transactionId = txnId,
                balanceCents = balance + amountCents,
                currency = currency,
                replay = false,
            )
        }
    }

    suspend fun listByUser(userId: String, limit: Int, cursor: String?): TransactionPage {
        val (ts, id) = decodeCursor(cursor)
        val pageSize = limit.coerceIn(1, MAX_LIMIT)
        val rows = tx.withTx { conn ->
            transactions.listByUser(
                conn = conn,
                userId = userId,
                limit = pageSize + 1, // one extra to detect "has more"
                cursorTs = ts,
                cursorId = id,
            )
        }
        val hasMore = rows.size > pageSize
        val items = if (hasMore) rows.subList(0, pageSize) else rows
        val nextCursor = if (hasMore) encodeCursor(items.last().createdAt, items.last().id) else null
        return TransactionPage(items = items, nextCursor = nextCursor)
    }

    private fun encodeCursor(ts: Instant, id: Long): String {
        val raw = "${ts.epochSecond}.${ts.nano}:$id"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray())
    }

    private fun decodeCursor(cursor: String?): Pair<Instant?, Long?> {
        if (cursor.isNullOrBlank()) return null to null
        return try {
            val raw = String(Base64.getUrlDecoder().decode(cursor))
            val (tsPart, idPart) = raw.split(":", limit = 2)
            val (sec, nano) = tsPart.split(".", limit = 2)
            Instant.ofEpochSecond(sec.toLong(), nano.toLong()) to idPart.toLong()
        } catch (_: Exception) {
            throw IllegalArgumentException("invalid cursor")
        }
    }

    companion object {
        const val MAX_LIMIT = 200
        private const val SQLSTATE_UNIQUE_VIOLATION = "23505"
    }
}

data class DepositResult(
    val transactionId: Long,
    val balanceCents: Long,
    val currency: String,
    val replay: Boolean,
)
