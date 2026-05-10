package com.stockyard.core.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import javax.sql.DataSource

/**
 * Bracket-обёртка над JDBC TX:
 *  - borrow connection из пула;
 *  - autoCommit=false;
 *  - block(conn);
 *  - commit / rollback при exception;
 *  - return connection в пул через .use {} (Closeable).
 *
 * IO выносится в Dispatchers.IO — Ktor coroutines free для остальной работы.
 *
 * Используется в TASK-005 (auth), TASK-006 (BUY/SELL TX по 07-consistency §7.2).
 */
class TransactionManager(private val ds: DataSource) {

    suspend fun <T> withTx(block: suspend (Connection) -> T): T = withContext(Dispatchers.IO) {
        ds.connection.use { conn ->
            val prevAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                val result = block(conn)
                conn.commit()
                result
            } catch (e: Throwable) {
                runCatching { conn.rollback() }
                throw e
            } finally {
                runCatching { conn.autoCommit = prevAutoCommit }
            }
        }
    }
}
