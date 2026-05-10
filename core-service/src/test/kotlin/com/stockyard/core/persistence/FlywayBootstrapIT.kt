package com.stockyard.core.persistence

import com.stockyard.core.test.PgFixture
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Проверяет, что Flyway-bootstrap корректно применяет существующие
 * миграции V1-V7 (см. db/migration/) и идемпотентен на повторном запуске.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FlywayBootstrapIT {

    private val pg: PostgreSQLContainer<*> = PgFixture.container()
    private lateinit var ds: HikariDataSource

    @BeforeAll
    fun setUp() {
        pg.start()
        ds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = pg.jdbcUrl
            username = pg.username
            password = pg.password
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 4
        })
    }

    @AfterAll
    fun tearDown() {
        runCatching { ds.close() }
        pg.stop()
    }

    @Test
    fun `first migration applies V1 through V7`() {
        FlywayBootstrap.migrate(ds)

        ds.connection.use { conn ->
            // 6 пользовательских таблиц + flyway_schema_history
            val expected = setOf(
                "users", "accounts", "instruments",
                "orders", "positions", "transactions",
                "flyway_schema_history",
            )
            val actual = mutableSetOf<String>()
            conn.prepareStatement(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public'"
            ).executeQuery().use { rs ->
                while (rs.next()) actual += rs.getString(1)
            }
            actual shouldContainAll expected
        }
    }

    @Test
    fun `instruments seed contains 50 tickers`() {
        // FlywayBootstrap.migrate уже вызван в первом тесте, проверяем DML из V2.
        ds.connection.use { conn ->
            conn.prepareStatement("SELECT count(*) FROM instruments").executeQuery().use { rs ->
                rs.next() shouldBe true
                rs.getInt(1) shouldBe 50
            }
        }
    }

    @Test
    fun `second migrate call is idempotent`() {
        // Повторный вызов: ничего не применяется, exception не бросается.
        FlywayBootstrap.migrate(ds)

        ds.connection.use { conn ->
            conn.prepareStatement(
                "SELECT count(*) FROM flyway_schema_history WHERE success = TRUE"
            ).executeQuery().use { rs ->
                rs.next() shouldBe true
                val applied = rs.getInt(1)
                // V1..V7 = 7 строк (с success=true). Поверх baseline (если был).
                (applied >= 7) shouldBe true
            }
        }
    }

    @Test
    fun `orders table enforces idempotency unique constraint`() {
        ds.connection.use { conn ->
            // Создаём предусловия: пользователь и инструмент уже сидятся V2 (SBER).
            conn.prepareStatement(
                "INSERT INTO users (id, email, password_hash) VALUES (?, ?, ?) ON CONFLICT DO NOTHING"
            ).use { ps ->
                ps.setString(1, "u_test_unique")
                ps.setString(2, "u_test_unique@stockyard.test")
                ps.setString(3, "\$argon2id\$test")
                ps.executeUpdate()
            }

            val insertOrder = conn.prepareStatement(
                """
                INSERT INTO orders (id, user_id, ticker, side, qty, status, idempotency_key)
                VALUES (?, 'u_test_unique', 'SBER', 'BUY', 10, 'PENDING', 'idem-key-1')
                """.trimIndent()
            )
            insertOrder.setString(1, "o_test_1"); insertOrder.executeUpdate()

            val duplicate = conn.prepareStatement(
                """
                INSERT INTO orders (id, user_id, ticker, side, qty, status, idempotency_key)
                VALUES (?, 'u_test_unique', 'SBER', 'BUY', 5, 'PENDING', 'idem-key-1')
                """.trimIndent()
            )
            duplicate.setString(1, "o_test_2")

            val threw = runCatching { duplicate.executeUpdate() }.isFailure
            threw shouldBe true   // UNIQUE(user_id, idempotency_key) сработал
        }
    }
}
