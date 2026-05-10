package com.stockyard.core.persistence

import com.stockyard.core.config.ClickHouseConfig
import com.stockyard.core.config.PostgresConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource

/**
 * Два HikariCP-пула:
 *  - **pg** — на PostgreSQL под бизнес-транзакции (BUY/SELL, см. 07-consistency).
 *  - **clickhouse** — на ClickHouse под чтение свечей (TASK-008).
 *
 * Параметры подобраны по docs/architecture/12-storage-operations.md §12.1.2 / §12.3.3.
 * После borrow connection — выставляем statement/lock/idle timeouts через SET, чтобы
 * длинные TX или забытые транзакции не подвешивали worker'ы.
 */
class DataSources(pgCfg: PostgresConfig, chCfg: ClickHouseConfig) : AutoCloseable {

    val pg: HikariDataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = pgCfg.jdbcUrl
            driverClassName = "org.postgresql.Driver"
            username = pgCfg.user
            password = pgCfg.password

            maximumPoolSize = 50
            minimumIdle = 10
            connectionTimeout = 1_000
            validationTimeout = 500
            idleTimeout = 600_000
            maxLifetime = 1_800_000
            keepaliveTime = 60_000
            leakDetectionThreshold = 5_000

            connectionTestQuery = "SELECT 1"
            initializationFailTimeout = 10_000

            // Connection-level timeouts (PG GUC). Применяется после connect через
            // initSql; гарантирует, что ни один statement не висит дольше 3 сек,
            // lock-ожидание — 2 сек, idle-in-transaction — 5 сек.
            connectionInitSql = """
                SET statement_timeout = 3000;
                SET lock_timeout = 2000;
                SET idle_in_transaction_session_timeout = 5000;
                SET application_name = 'core-service';
            """.trimIndent()

            poolName = "core-pg"
        }
    )

    val clickhouse: HikariDataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = chCfg.jdbcUrl
            driverClassName = "com.clickhouse.jdbc.ClickHouseDriver"
            username = chCfg.user
            password = chCfg.password

            maximumPoolSize = 8
            minimumIdle = 2
            connectionTimeout = 2_000
            idleTimeout = 300_000
            maxLifetime = 1_800_000

            connectionTestQuery = "SELECT 1 FORMAT TabSeparated"

            poolName = "core-ch"
        }
    )

    /** Простой PING для readiness — выполняет `SELECT 1` через пул. */
    fun pgPing(): Boolean = runCatching {
        pg.connection.use { it.prepareStatement("SELECT 1").executeQuery().use { rs -> rs.next() } }
    }.getOrElse { false }

    fun clickhousePing(): Boolean = runCatching {
        clickhouse.connection.use {
            it.prepareStatement("SELECT 1 FORMAT TabSeparated").executeQuery().use { rs -> rs.next() }
        }
    }.getOrElse { false }

    override fun close() {
        runCatching { pg.close() }
        runCatching { clickhouse.close() }
    }
}

/** Удобный alias для случаев когда сигнатура DataSource достаточна. */
val DataSources.pgDataSource: DataSource get() = pg
