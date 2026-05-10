package com.stockyard.core.domain.account

import java.sql.Connection

/**
 * Raw JDBC доступ к `accounts`. Все мутации балансов — в одной транзакции через
 * [com.stockyard.core.persistence.TransactionManager] с обязательной блокировкой
 * через [findBalanceForUpdate] до записи (07-consistency §7.2 + ADR-004).
 */
class AccountRepository {

    /**
     * Блокирует строку счёта пользователя по валюте до COMMIT. Возвращает текущий
     * баланс в копейках. null, если счёта в указанной валюте не существует
     * (для зарегистрированного через TASK-005 пользователя такая ситуация не возникает —
     * RUB-счёт создаётся в одной TX с INSERT users).
     */
    fun findBalanceForUpdate(conn: Connection, userId: String, currency: String): Long? =
        conn.prepareStatement(
            "SELECT balance_cents FROM accounts " +
                "WHERE user_id = ? AND currency = ? FOR UPDATE",
        ).use { ps ->
            ps.setString(1, userId)
            ps.setString(2, currency)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong("balance_cents") else null }
        }

    /**
     * Применяет дельту к балансу. Положительная — приход (SELL proceeds, DEPOSIT),
     * отрицательная — расход (BUY cost). Вызывающий гарантирует, что строка уже
     * заблокирована через [findBalanceForUpdate] и проверка balance >= |delta| (для отрицательных)
     * уже сделана.
     */
    fun applyDelta(conn: Connection, userId: String, currency: String, deltaCents: Long) {
        conn.prepareStatement(
            "UPDATE accounts SET balance_cents = balance_cents + ?, updated_at = now() " +
                "WHERE user_id = ? AND currency = ?",
        ).use { ps ->
            ps.setLong(1, deltaCents)
            ps.setString(2, userId)
            ps.setString(3, currency)
            ps.executeUpdate()
        }
    }
}
