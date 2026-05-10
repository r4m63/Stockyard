package com.stockyard.core.persistence

import com.stockyard.core.config.ClickHouseConfig
import com.stockyard.core.config.PostgresConfig
import com.stockyard.core.test.PgFixture
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DataSourcesIT {

    private val pg: PostgreSQLContainer<*> = PgFixture.container()
    private lateinit var ds: DataSources

    @BeforeAll
    fun setUp() {
        pg.start()
        ds = DataSources(
            pgCfg = PostgresConfig(
                host = pg.host,
                port = pg.firstMappedPort,
                db = pg.databaseName,
                user = pg.username,
                password = pg.password,
            ),
            chCfg = ClickHouseConfig(
                host = "127.0.0.1",   // намеренно недоступно — clickhousePing должен вернуть false
                port = 65535,
                db = "stockyard",
                user = "stockyard",
                password = "stockyard",
            ),
        )
    }

    @AfterAll
    fun tearDown() {
        ds.close()
        pg.stop()
    }

    @Test
    fun `pgPing returns true with live PostgreSQL`() {
        ds.pgPing() shouldBe true
    }

    @Test
    fun `clickhousePing returns false when ClickHouse unreachable`() {
        // CH-host намеренно закрыт; ping не должен бросать, должен вернуть false.
        ds.clickhousePing() shouldBe false
    }

    @Test
    fun `connectionInitSql sets statement_timeout on PG`() {
        ds.pg.connection.use { conn ->
            conn.prepareStatement("SHOW statement_timeout").executeQuery().use { rs ->
                rs.next() shouldBe true
                // PG возвращает "3s" или "3000ms" — нормализуем
                val value = rs.getString(1)
                (value == "3s" || value == "3000ms") shouldBe true
            }
        }
    }

    @Test
    fun `connectionInitSql sets application_name`() {
        ds.pg.connection.use { conn ->
            conn.prepareStatement("SHOW application_name").executeQuery().use { rs ->
                rs.next() shouldBe true
                rs.getString(1) shouldBe "core-service"
            }
        }
    }
}
