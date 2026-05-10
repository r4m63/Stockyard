package com.stockyard.core.domain.order

import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64

/**
 * Raw JDBC репозиторий по `orders`. ORM запрещён — CLAUDE.md.
 * Управление TX — в [OrderService] через [com.stockyard.core.persistence.TransactionManager].
 *
 * UNIQUE (user_id, idempotency_key) — это страховка из ADR-005. Основная защита от race
 * по этому ключу — `SELECT ... FOR UPDATE` в [findByUserAndIdempotencyKey].
 */
class OrderRepository {

    /**
     * Ищет ордер по idempotency-ключу. С [lock]=true — блокирует строку до конца TX
     * через `SELECT ... FOR UPDATE`, что сериализует конкурентные запросы с одним ключом.
     * Возвращает null, если ордера ещё нет.
     */
    fun findByUserAndIdempotencyKey(
        conn: Connection,
        userId: String,
        idempotencyKey: String,
        lock: Boolean,
    ): Order? {
        val sql = """
            SELECT id, user_id, ticker, side, qty, price_cents, status,
                   idempotency_key, created_at, executed_at
            FROM orders
            WHERE user_id = ? AND idempotency_key = ?
        """.trimIndent() + if (lock) " FOR UPDATE" else ""
        return conn.prepareStatement(sql).use { ps ->
            ps.setString(1, userId)
            ps.setString(2, idempotencyKey)
            ps.executeQuery().use { rs -> if (rs.next()) mapRow(rs) else null }
        }
    }

    fun insert(conn: Connection, order: Order) {
        conn.prepareStatement(
            """
            INSERT INTO orders
                (id, user_id, ticker, side, qty, price_cents, status,
                 idempotency_key, created_at, executed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, order.id)
            ps.setString(2, order.userId)
            ps.setString(3, order.ticker)
            ps.setString(4, order.side.name)
            ps.setInt(5, order.qty)
            if (order.priceCents != null) ps.setLong(6, order.priceCents) else ps.setNull(6, java.sql.Types.BIGINT)
            ps.setString(7, order.status.name)
            ps.setString(8, order.idempotencyKey)
            ps.setObject(9, Timestamp.from(order.createdAt))
            ps.setObject(10, order.executedAt?.let(Timestamp::from))
            ps.executeUpdate()
        }
    }

    /**
     * Keyset-пагинация по `(created_at DESC, id DESC)` — индекс `idx_orders_user_created` (V6).
     * `cursor` — base64("createdAt_ms|orderId"). null → начало списка.
     * Возвращает [Page] ровно с [limit] ордерами + nextCursor (null если конец).
     */
    fun listByUser(
        conn: Connection,
        userId: String,
        statusFilter: OrderStatus?,
        limit: Int,
        cursor: String?,
    ): Page<Order> {
        val cursorParts = cursor?.let { decodeCursor(it) }
        val sql = buildString {
            append(
                """
                SELECT id, user_id, ticker, side, qty, price_cents, status,
                       idempotency_key, created_at, executed_at
                FROM orders
                WHERE user_id = ?
                """.trimIndent(),
            )
            if (statusFilter != null) append(" AND status = ?")
            if (cursorParts != null) append(" AND (created_at, id) < (?, ?)")
            append(" ORDER BY created_at DESC, id DESC LIMIT ?")
        }
        val items = conn.prepareStatement(sql).use { ps ->
            var i = 1
            ps.setString(i++, userId)
            if (statusFilter != null) ps.setString(i++, statusFilter.name)
            if (cursorParts != null) {
                ps.setObject(i++, Timestamp.from(cursorParts.first))
                ps.setString(i++, cursorParts.second)
            }
            ps.setInt(i, limit + 1)  // +1 чтобы знать, есть ли следующая страница
            ps.executeQuery().use { rs ->
                val acc = mutableListOf<Order>()
                while (rs.next()) acc += mapRow(rs)
                acc
            }
        }
        val hasMore = items.size > limit
        val page = if (hasMore) items.subList(0, limit) else items
        val nextCursor = if (hasMore) {
            val last = page.last()
            encodeCursor(last.createdAt, last.id)
        } else {
            null
        }
        return Page(page, nextCursor)
    }

    private fun mapRow(rs: ResultSet): Order = Order(
        id = rs.getString("id"),
        userId = rs.getString("user_id"),
        ticker = rs.getString("ticker"),
        side = OrderSide.valueOf(rs.getString("side")),
        qty = rs.getInt("qty"),
        priceCents = rs.getObject("price_cents") as Long?,
        status = OrderStatus.valueOf(rs.getString("status")),
        idempotencyKey = rs.getString("idempotency_key"),
        createdAt = rs.getTimestamp("created_at").toInstant(),
        executedAt = rs.getTimestamp("executed_at")?.toInstant(),
    )

    companion object {
        private val ENC = Base64.getUrlEncoder().withoutPadding()
        private val DEC = Base64.getUrlDecoder()

        fun encodeCursor(createdAt: Instant, orderId: String): String =
            ENC.encodeToString("${createdAt.toEpochMilli()}|$orderId".toByteArray(Charsets.UTF_8))

        fun decodeCursor(cursor: String): Pair<Instant, String> {
            val decoded = String(DEC.decode(cursor), Charsets.UTF_8)
            val parts = decoded.split('|', limit = 2)
            require(parts.size == 2) { "malformed cursor" }
            return Instant.ofEpochMilli(parts[0].toLong()) to parts[1]
        }
    }
}

data class Page<T>(val items: List<T>, val nextCursor: String?)
