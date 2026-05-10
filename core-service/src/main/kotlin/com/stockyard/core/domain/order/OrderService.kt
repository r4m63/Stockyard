package com.stockyard.core.domain.order

import com.stockyard.core.domain.IdGen
import com.stockyard.core.domain.account.AccountRepository
import com.stockyard.core.domain.instrument.InstrumentRepository
import com.stockyard.core.domain.position.PositionRepository
import com.stockyard.core.domain.transaction.TransactionRepository
import com.stockyard.core.domain.transaction.TxnType
import com.stockyard.core.persistence.TransactionManager
import com.stockyard.core.quotes.QuotesPort
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.time.Instant

/**
 * Application service по ордерам. Объединяет валидацию, чтение текущей цены
 * (Redis HGET до старта TX) и атомарное исполнение в PostgreSQL с FOR UPDATE
 * на accounts (BUY) или positions (SELL).
 *
 * Идемпотентность — через `SELECT ... FOR UPDATE WHERE idempotency_key = ?` на старте TX;
 * UNIQUE (user_id, idempotency_key) — страховка (ADR-005). Полный flow — TASK-006 §6.
 *
 * Audit-запись в `transactions` пишем ТОЛЬКО на EXECUTED (TASK-006 §10).
 */
class OrderService(
    private val tx: TransactionManager,
    private val instruments: InstrumentRepository,
    private val orders: OrderRepository,
    private val accounts: AccountRepository,
    private val positions: PositionRepository,
    private val transactions: TransactionRepository,
    private val quotes: QuotesPort,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Размещает ордер и сразу его исполняет (BUY) либо отклоняет (REJECTED при недостатке
     * средств / позиции). Возвращает результирующий [Order].
     *
     * Гарантии:
     *  - Конкурентные вызовы с одним [idempotencyKey] от одного userId дают ровно один INSERT,
     *    остальные возвращают тот же ордер (200 OK в API).
     *  - Концерт BUY одного пользователя при балансе на один ордер: ровно один EXECUTED,
     *    остальные REJECTED, баланс не уходит в минус.
     */
    suspend fun place(
        userId: String,
        ticker: String,
        side: OrderSide,
        qty: Int,
        idempotencyKey: String,
    ): Order {
        validateQty(qty)
        require(idempotencyKey.isNotBlank()) { "idempotency key must be non-empty" }

        // Цена читается ДО TX (CLAUDE.md «Деньги»). Если цены нет — короткое замыкание.
        val priceCents = when (side) {
            OrderSide.BUY -> quotes.getAsk(ticker)
            OrderSide.SELL -> quotes.getBid(ticker)
        } ?: throw NoQuoteAvailableException(ticker)

        // Снаружи TX: после commit бросаем exception на REJECTED, чтобы НЕ вызвать ROLLBACK
        // (REJECTED-ордер должен попасть в audit history).
        val outcome = tx.withTx { conn ->
            // Idempotency: ищем существующий ордер под блокировкой.
            val existing = orders.findByUserAndIdempotencyKey(conn, userId, idempotencyKey, lock = true)
            if (existing != null) {
                if (existing.ticker != ticker || existing.side != side || existing.qty != qty) {
                    throw IdempotencyConflictException()
                }
                // Idempotent повтор. Если первый раз был REJECTED — повторно собираем reason
                // (актуальное state в БД).
                val replay = if (existing.status == OrderStatus.REJECTED) {
                    rejectionInfo(conn, existing.side, existing.userId, existing.ticker)
                } else {
                    null
                }
                return@withTx Outcome(existing, replay)
            }

            // Проверка тикера — после idempotency-короткого замыкания, иначе повтор плохого
            // ticker съест существующий ордер.
            if (!instruments.existsTicker(conn, ticker)) {
                throw InvalidTickerException(ticker)
            }

            val newOrderId = IdGen.orderId()
            val now = Instant.now()
            val result = when (side) {
                OrderSide.BUY -> executeBuy(conn, userId, ticker, qty, priceCents, idempotencyKey, newOrderId, now)
                OrderSide.SELL -> executeSell(conn, userId, ticker, qty, priceCents, idempotencyKey, newOrderId, now)
            }
            try {
                orders.insert(conn, result)
            } catch (e: SQLException) {
                // 23505 на UNIQUE(user_id, idempotency_key) — race с параллельным INSERT, который
                // успел проскочить между нашим SELECT FOR UPDATE и INSERT. Маловероятно при
                // явной блокировке, но возможно при отказе в lock и retry — мапим в conflict.
                if (e.sqlState == SQLSTATE_UNIQUE_VIOLATION) {
                    throw IdempotencyConflictException("race on idempotency key")
                }
                throw e
            }

            val rejInfo = if (result.status == OrderStatus.EXECUTED) {
                val auditAmount = when (side) {
                    OrderSide.BUY -> -priceCents * qty
                    OrderSide.SELL -> priceCents * qty
                }
                transactions.insertAudit(
                    conn,
                    userId,
                    when (side) { OrderSide.BUY -> TxnType.BUY; OrderSide.SELL -> TxnType.SELL },
                    auditAmount,
                    result.id,
                )
                null
            } else {
                // REJECTED — собираем reason, актуальное state ещё под блокировкой.
                rejectionInfo(conn, side, userId, ticker)
            }

            log.atInfo()
                .addKeyValue("user.id", userId)
                .addKeyValue("order.id", result.id)
                .addKeyValue("order.side", side.name)
                .addKeyValue("order.status", result.status.name)
                .addKeyValue("order.ticker", ticker)
                .addKeyValue("order.qty", qty)
                .addKeyValue("order.priceCents", priceCents)
                .log("order processed")

            Outcome(result, rejInfo)
        }

        // TX committed. Если ордер REJECTED — бросаем business exception → ErrorMapper → 422.
        // INSERT REJECTED-ордера и audit остаются в БД (для пользовательской истории).
        outcome.rejection?.let { rej ->
            throw when (rej) {
                is RejectionReason.Funds -> InsufficientFundsException(
                    requiredCents = priceCents * qty,
                    availableCents = rej.availableCents,
                )
                is RejectionReason.Position -> InsufficientPositionException(
                    requiredQty = qty,
                    availableQty = rej.availableQty,
                )
            }
        }
        return outcome.order
    }

    private fun rejectionInfo(
        conn: java.sql.Connection,
        side: OrderSide,
        userId: String,
        ticker: String,
    ): RejectionReason = when (side) {
        OrderSide.BUY -> RejectionReason.Funds(
            availableCents = accounts.findBalanceForUpdate(conn, userId, "RUB") ?: 0L,
        )
        OrderSide.SELL -> RejectionReason.Position(
            availableQty = positions.findForUpdate(conn, userId, ticker)?.qty ?: 0,
        )
    }

    private data class Outcome(val order: Order, val rejection: RejectionReason?)

    private sealed interface RejectionReason {
        data class Funds(val availableCents: Long) : RejectionReason
        data class Position(val availableQty: Int) : RejectionReason
    }

    suspend fun listByUser(
        userId: String,
        statusFilter: OrderStatus?,
        limit: Int,
        cursor: String?,
    ): Page<Order> = tx.withTx { conn ->
        orders.listByUser(conn, userId, statusFilter, limit.coerceIn(1, MAX_LIST_LIMIT), cursor)
    }

    private fun executeBuy(
        conn: java.sql.Connection,
        userId: String,
        ticker: String,
        qty: Int,
        priceCents: Long,
        idempotencyKey: String,
        orderId: String,
        now: Instant,
    ): Order {
        val cost = priceCents * qty
        val balance = accounts.findBalanceForUpdate(conn, userId, "RUB")
            ?: throw IllegalStateException("RUB account missing for user $userId")

        if (balance < cost) {
            return Order(
                id = orderId, userId = userId, ticker = ticker, side = OrderSide.BUY,
                qty = qty, priceCents = priceCents, status = OrderStatus.REJECTED,
                idempotencyKey = idempotencyKey, createdAt = now, executedAt = now,
            )
        }
        accounts.applyDelta(conn, userId, "RUB", -cost)
        positions.upsertOnBuy(conn, userId, ticker, qty, priceCents)
        return Order(
            id = orderId, userId = userId, ticker = ticker, side = OrderSide.BUY,
            qty = qty, priceCents = priceCents, status = OrderStatus.EXECUTED,
            idempotencyKey = idempotencyKey, createdAt = now, executedAt = now,
        )
    }

    private fun executeSell(
        conn: java.sql.Connection,
        userId: String,
        ticker: String,
        qty: Int,
        priceCents: Long,
        idempotencyKey: String,
        orderId: String,
        now: Instant,
    ): Order {
        val position = positions.findForUpdate(conn, userId, ticker)
        val availableQty = position?.qty ?: 0
        if (availableQty < qty) {
            return Order(
                id = orderId, userId = userId, ticker = ticker, side = OrderSide.SELL,
                qty = qty, priceCents = priceCents, status = OrderStatus.REJECTED,
                idempotencyKey = idempotencyKey, createdAt = now, executedAt = now,
            )
        }
        positions.decreaseQty(conn, userId, ticker, qty)
        val proceeds = priceCents * qty
        accounts.applyDelta(conn, userId, "RUB", proceeds)
        return Order(
            id = orderId, userId = userId, ticker = ticker, side = OrderSide.SELL,
            qty = qty, priceCents = priceCents, status = OrderStatus.EXECUTED,
            idempotencyKey = idempotencyKey, createdAt = now, executedAt = now,
        )
    }

    private fun validateQty(qty: Int) {
        if (qty <= 0 || qty > MAX_QTY) throw InvalidQuantityException(qty)
    }

    companion object {
        const val MAX_QTY = 1_000_000
        const val MAX_LIST_LIMIT = 200
        private const val SQLSTATE_UNIQUE_VIOLATION = "23505"
    }
}
