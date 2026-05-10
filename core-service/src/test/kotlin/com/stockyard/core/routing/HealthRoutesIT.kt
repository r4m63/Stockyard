package com.stockyard.core.routing

import com.stockyard.core.test.PgFixture
import com.stockyard.core.test.RedisFixture
import com.stockyard.core.test.installTestModule
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HealthRoutesIT {

    private val pg: PostgreSQLContainer<*> = PgFixture.container()
    private val redis: GenericContainer<*> = RedisFixture.container()

    @BeforeAll fun start() { pg.start(); redis.start() }
    @AfterAll  fun stop()  { pg.stop(); redis.stop() }

    private val redisUrl get() = "redis://${redis.host}:${redis.firstMappedPort}"

    @Test
    fun `live returns 200 UP regardless of downstream`() = testApplication {
        installTestModule(
            pgHost = pg.host, pgPort = pg.firstMappedPort,
            pgUser = pg.username, pgPassword = pg.password,
            redisUrl = redisUrl,
        )
        val resp = client.get("/health/live")
        resp.status shouldBe HttpStatusCode.OK
        resp.bodyAsText() shouldContain "\"status\":\"UP\""
    }

    @Test
    fun `ready returns 200 with PG plus Redis up; CH info-only DOWN`() = testApplication {
        installTestModule(
            pgHost = pg.host, pgPort = pg.firstMappedPort,
            pgUser = pg.username, pgPassword = pg.password,
            redisUrl = redisUrl,
        )
        val resp = client.get("/health/ready")
        resp.status shouldBe HttpStatusCode.OK    // CH down но это info-only
        val body = resp.bodyAsText()
        body shouldContain "\"postgres\":\"UP\""
        body shouldContain "\"redis\":\"UP\""
        body shouldContain "\"clickhouse\":\"DOWN\""
    }

    @Test
    fun `ready returns 503 when PG unreachable`() = testApplication {
        installTestModule(
            pgHost = "127.0.0.1", pgPort = 1,        // намеренно мёртвый PG
            pgUser = "stockyard", pgPassword = "stockyard",
            redisUrl = redisUrl,
        )
        val resp = client.get("/health/ready")
        resp.status shouldBe HttpStatusCode.ServiceUnavailable
        resp.bodyAsText() shouldContain "\"postgres\":\"DOWN\""
    }

    @Test
    fun `ready returns 503 when Redis unreachable`() = testApplication {
        installTestModule(
            pgHost = pg.host, pgPort = pg.firstMappedPort,
            pgUser = pg.username, pgPassword = pg.password,
            redisUrl = "redis://127.0.0.1:1",        // намеренно мёртвый Redis
        )
        val resp = client.get("/health/ready")
        resp.status shouldBe HttpStatusCode.ServiceUnavailable
        resp.bodyAsText() shouldContain "\"redis\":\"DOWN\""
    }

    @Test
    fun `metrics endpoint exposes prometheus format`() = testApplication {
        installTestModule(
            pgHost = pg.host, pgPort = pg.firstMappedPort,
            pgUser = pg.username, pgPassword = pg.password,
            redisUrl = redisUrl,
        )
        // Сначала запрос чтобы появились HTTP-метрики.
        client.get("/health/live")

        val resp = client.get("/metrics")
        resp.status shouldBe HttpStatusCode.OK

        val body = resp.bodyAsText()
        // Prometheus exposition format: # TYPE / # HELP заголовки + метрики JVM.
        body shouldContain "# TYPE"
        body shouldContain "jvm_memory_used_bytes"
    }
}
