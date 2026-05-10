package com.stockyard.core.domain.position

import java.sql.Connection

/**
 * Raw JDBC доступ к `positions`. Все операции принимают [Connection] и предполагают
 * наличие открытой транзакции в вызывающем коде.
 */
class PositionRepository {

    /**
     * Блокирует строку позиции на чтение/запись до COMMIT.
     * Возвращает null, если у пользователя нет позиции по тикеру.
     */
    fun findForUpdate(conn: Connection, userId: String, ticker: String): Position? =
        conn.prepareStatement(
            "SELECT user_id, ticker, qty, avg_price_cents FROM positions " +
                "WHERE user_id = ? AND ticker = ? FOR UPDATE",
        ).use { ps ->
            ps.setString(1, userId)
            ps.setString(2, ticker)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    Position(
                        userId = rs.getString("user_id"),
                        ticker = rs.getString("ticker"),
                        qty = rs.getInt("qty"),
                        avgPriceCents = rs.getLong("avg_price_cents"),
                    )
                } else {
                    null
                }
            }
        }

    /**
     * Атомарный upsert на BUY. Если позиция уже есть — складываем qty и
     * пересчитываем взвешенную среднюю цену:
     *     new_avg = (old_avg * old_qty + buy_price * buy_qty) / (old_qty + buy_qty)
     * Если позиции нет — создаём новую с avg_price = buy_price.
     *
     * Используется при PERIPHERAL BUY-flow в TX, после блокировки accounts.
     */
    fun upsertOnBuy(
        conn: Connection,
        userId: String,
        ticker: String,
        addQty: Int,
        atPriceCents: Long,
    ) {
        conn.prepareStatement(
            """
            INSERT INTO positions (user_id, ticker, qty, avg_price_cents)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (user_id, ticker) DO UPDATE
            SET qty = positions.qty + EXCLUDED.qty,
                avg_price_cents = (
                    positions.avg_price_cents * positions.qty
                    + EXCLUDED.avg_price_cents * EXCLUDED.qty
                ) / (positions.qty + EXCLUDED.qty),
                updated_at = now()
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, userId)
            ps.setString(2, ticker)
            ps.setInt(3, addQty)
            ps.setLong(4, atPriceCents)
            ps.executeUpdate()
        }
    }

    /**
     * SELL: уменьшает qty на заданное количество. avg_price НЕ меняется (см. 07-consistency §7.2.4).
     * Возвращает количество обновлённых строк (0 если позиции нет).
     */
    fun decreaseQty(conn: Connection, userId: String, ticker: String, byQty: Int): Int =
        conn.prepareStatement(
            "UPDATE positions SET qty = qty - ?, updated_at = now() " +
                "WHERE user_id = ? AND ticker = ?",
        ).use { ps ->
            ps.setInt(1, byQty)
            ps.setString(2, userId)
            ps.setString(3, ticker)
            ps.executeUpdate()
        }
}
