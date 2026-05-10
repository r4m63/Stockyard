package com.stockyard.core.domain.user

import java.sql.Connection
import java.sql.SQLException

/**
 * Raw JDBC репозиторий по users + accounts. ORM запрещён — CLAUDE.md «Конвенции».
 * Все методы принимают `Connection` — управление TX отдано вызывающему ([TransactionManager]).
 *
 * Soft-contract: ловит SQLState `23505` (unique_violation) на `users.email` →
 * бросает [EmailTakenException]. Остальные SQLException пробрасывает наверх.
 */
class UserRepository {

    /**
     * Вставка пользователя. PG бросит 23505 при дубле email — конвертируем
     * в [EmailTakenException] для маппинга в HTTP 409.
     */
    fun insert(conn: Connection, user: User) {
        try {
            conn.prepareStatement(
                "INSERT INTO users (id, email, password_hash, created_at) VALUES (?, ?, ?, ?)",
            ).use { ps ->
                ps.setString(1, user.id)
                ps.setString(2, user.email)
                ps.setString(3, user.passwordHash)
                ps.setObject(4, java.sql.Timestamp.from(user.createdAt))
                ps.executeUpdate()
            }
        } catch (e: SQLException) {
            if (e.sqlState == SQLSTATE_UNIQUE_VIOLATION) {
                throw EmailTakenException(user.email)
            }
            throw e
        }
    }

    fun findByEmail(conn: Connection, email: String): User? =
        conn.prepareStatement(
            "SELECT id, email, password_hash, created_at FROM users WHERE email = ?",
        ).use { ps ->
            ps.setString(1, email)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    User(
                        id = rs.getString("id"),
                        email = rs.getString("email"),
                        passwordHash = rs.getString("password_hash"),
                        createdAt = rs.getTimestamp("created_at").toInstant(),
                    )
                } else {
                    null
                }
            }
        }

    /**
     * Создаёт RUB-счёт с начальным депозитом. Цена — `Long` cents.
     * UNIQUE(user_id, currency) защитит от двойного создания.
     */
    fun insertAccount(conn: Connection, userId: String, balanceCents: Long, currency: String) {
        conn.prepareStatement(
            "INSERT INTO accounts (user_id, balance_cents, currency) VALUES (?, ?, ?)",
        ).use { ps ->
            ps.setString(1, userId)
            ps.setLong(2, balanceCents)
            ps.setString(3, currency)
            ps.executeUpdate()
        }
    }

    companion object {
        private const val SQLSTATE_UNIQUE_VIOLATION = "23505"
    }
}
