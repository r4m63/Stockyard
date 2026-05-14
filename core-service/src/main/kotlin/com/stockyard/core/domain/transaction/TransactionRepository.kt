package com.stockyard.core.domain.transaction

import java.sql.Connection
import java.time.Instant

enum class TxnType { DEPOSIT, BUY, SELL }

data class TransactionRecord(
    val id: Long,
    val userId: String,
    val type: TxnType,
    val amountCents: Long,
    val refOrderId: String?,
    val idempotencyKey: String?,
    val createdAt: Instant,
)

data class TransactionPage(val items: List<TransactionRecord>, val nextCursor: String?)

/**
 * Audit-лог денежных движений (BUY/SELL — TASK-006; DEPOSIT — TASK-014).
 *
 * `amountCents`: отрицательное на BUY (списание), положительное на SELL/DEPOSIT (приход).
 * `idempotency_key` опционален: legacy BUY/SELL audit-строки (до TASK-014) не имеют ключа;
 * DEPOSIT всегда с ключом (UNIQUE partial index, ADR-011 / TASK-014).
 */
class TransactionRepository {

    fun insertAudit(
        conn: Connection,
        userId: String,
        type: TxnType,
        amountCents: Long,
        refOrderId: String?,
    ) {
        conn.prepareStatement(
            "INSERT INTO transactions (user_id, type, amount_cents, ref_order_id) " +
                "VALUES (?, ?, ?, ?)",
        ).use { ps ->
            ps.setString(1, userId)
            ps.setString(2, type.name)
            ps.setLong(3, amountCents)
            ps.setString(4, refOrderId)
            ps.executeUpdate()
        }
    }

    /**
     * Вставка с идемпотентным ключом (DEPOSIT). При коллизии бросает SQLException
     * со SQLState 23505; вызывающий должен сначала [findByIdempotency] под FOR UPDATE.
     * Возвращает сгенерированный BIGSERIAL id.
     */
    fun insertWithIdempotency(
        conn: Connection,
        userId: String,
        type: TxnType,
        amountCents: Long,
        idempotencyKey: String,
        refOrderId: String? = null,
    ): Long =
        conn.prepareStatement(
            "INSERT INTO transactions (user_id, type, amount_cents, ref_order_id, idempotency_key) " +
                "VALUES (?, ?, ?, ?, ?) RETURNING id",
        ).use { ps ->
            ps.setString(1, userId)
            ps.setString(2, type.name)
            ps.setLong(3, amountCents)
            ps.setString(4, refOrderId)
            ps.setString(5, idempotencyKey)
            ps.executeQuery().use { rs ->
                check(rs.next()) { "INSERT ... RETURNING produced no row" }
                rs.getLong(1)
            }
        }

    /** Lookup идемпотентного повтора. Возвращает null если ключа ещё нет. */
    fun findByIdempotency(
        conn: Connection,
        userId: String,
        type: TxnType,
        idempotencyKey: String,
    ): TransactionRecord? =
        conn.prepareStatement(
            "SELECT id, user_id, type, amount_cents, ref_order_id, idempotency_key, created_at " +
                "FROM transactions " +
                "WHERE user_id = ? AND type = ? AND idempotency_key = ?",
        ).use { ps ->
            ps.setString(1, userId)
            ps.setString(2, type.name)
            ps.setString(3, idempotencyKey)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toRecord() else null }
        }

    /**
     * Cursor-пагинация по `(created_at DESC, id DESC)`. Курсор — base64-кодированный
     * `"${epochMicros}:${id}"`; декодирование в сервисе.
     */
    fun listByUser(
        conn: Connection,
        userId: String,
        limit: Int,
        cursorTs: Instant?,
        cursorId: Long?,
    ): List<TransactionRecord> {
        val sql = if (cursorTs == null) {
            "SELECT id, user_id, type, amount_cents, ref_order_id, idempotency_key, created_at " +
                "FROM transactions WHERE user_id = ? " +
                "ORDER BY created_at DESC, id DESC LIMIT ?"
        } else {
            "SELECT id, user_id, type, amount_cents, ref_order_id, idempotency_key, created_at " +
                "FROM transactions " +
                "WHERE user_id = ? AND (created_at, id) < (?, ?) " +
                "ORDER BY created_at DESC, id DESC LIMIT ?"
        }
        return conn.prepareStatement(sql).use { ps ->
            ps.setString(1, userId)
            if (cursorTs == null) {
                ps.setInt(2, limit)
            } else {
                ps.setObject(2, java.sql.Timestamp.from(cursorTs))
                ps.setLong(3, cursorId ?: 0L)
                ps.setInt(4, limit)
            }
            ps.executeQuery().use { rs ->
                val acc = mutableListOf<TransactionRecord>()
                while (rs.next()) acc += rs.toRecord()
                acc
            }
        }
    }

    private fun java.sql.ResultSet.toRecord(): TransactionRecord = TransactionRecord(
        id = getLong("id"),
        userId = getString("user_id"),
        type = TxnType.valueOf(getString("type")),
        amountCents = getLong("amount_cents"),
        refOrderId = getString("ref_order_id"),
        idempotencyKey = getString("idempotency_key"),
        createdAt = getTimestamp("created_at").toInstant(),
    )
}
