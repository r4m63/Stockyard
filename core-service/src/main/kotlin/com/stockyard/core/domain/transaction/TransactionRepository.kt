package com.stockyard.core.domain.transaction

import java.sql.Connection

enum class TxnType { DEPOSIT, BUY, SELL }

/**
 * Audit-лог денежных движений. Пишется только при УСПЕШНОМ исполнении ордера
 * (см. TASK-006 §10 — на REJECTED не пишем).
 *
 * `amountCents`: отрицательное на BUY (списание), положительное на SELL/DEPOSIT (приход).
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
}
