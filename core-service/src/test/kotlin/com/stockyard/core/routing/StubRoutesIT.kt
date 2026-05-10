package com.stockyard.core.routing

import com.stockyard.core.test.PgFixture
import com.stockyard.core.test.RedisFixture
import com.stockyard.core.test.installTestModule
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
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

/**
 * Проверка что все 7 internal-эндпоинтов из 05-communication §5.4.2
 * возвращают 501 NOT_IMPLEMENTED в едином формате ошибок.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StubRoutesIT {

    private val pg: PostgreSQLContainer<*> = PgFixture.container()
    private val redis: GenericContainer<*> = RedisFixture.container()

    @BeforeAll fun start() { pg.start(); redis.start() }
    @AfterAll  fun stop()  { pg.stop(); redis.stop() }

    private fun ApplicationTestBuilderInstall() = Unit  // helper marker

    private suspend fun assertNotImplemented(resp: HttpResponse) {
        resp.status shouldBe HttpStatusCode.NotImplemented
        val body = resp.bodyAsText()
        body shouldContain "\"error\""
        body shouldContain "\"code\":\"NOT_IMPLEMENTED\""
    }

    @Test
    fun `POST internal users returns 501`() = testApplication {
        installTestModule(pgHost = pg.host, pgPort = pg.firstMappedPort,
            pgUser = pg.username, pgPassword = pg.password,
            redisUrl = "redis://${redis.host}:${redis.firstMappedPort}")
        assertNotImplemented(client.post("/internal/users"))
    }

    @Test
    fun `POST internal auth returns 501`() = testApplication {
        installTestModule(pgHost = pg.host, pgPort = pg.firstMappedPort,
            pgUser = pg.username, pgPassword = pg.password,
            redisUrl = "redis://${redis.host}:${redis.firstMappedPort}")
        assertNotImplemented(client.post("/internal/auth"))
    }

    @Test
    fun `POST internal orders returns 501`() = testApplication {
        installTestModule(pgHost = pg.host, pgPort = pg.firstMappedPort,
            pgUser = pg.username, pgPassword = pg.password,
            redisUrl = "redis://${redis.host}:${redis.firstMappedPort}")
        assertNotImplemented(client.post("/internal/orders"))
    }

    @Test
    fun `GET internal users orders returns 501`() = testApplication {
        installTestModule(pgHost = pg.host, pgPort = pg.firstMappedPort,
            pgUser = pg.username, pgPassword = pg.password,
            redisUrl = "redis://${redis.host}:${redis.firstMappedPort}")
        assertNotImplemented(client.get("/internal/users/u_test/orders"))
    }

    @Test
    fun `GET internal users portfolio returns 501`() = testApplication {
        installTestModule(pgHost = pg.host, pgPort = pg.firstMappedPort,
            pgUser = pg.username, pgPassword = pg.password,
            redisUrl = "redis://${redis.host}:${redis.firstMappedPort}")
        assertNotImplemented(client.get("/internal/users/u_test/portfolio"))
    }

    @Test
    fun `GET internal instruments returns 501`() = testApplication {
        installTestModule(pgHost = pg.host, pgPort = pg.firstMappedPort,
            pgUser = pg.username, pgPassword = pg.password,
            redisUrl = "redis://${redis.host}:${redis.firstMappedPort}")
        assertNotImplemented(client.get("/internal/instruments"))
    }

    @Test
    fun `GET internal quotes history returns 501`() = testApplication {
        installTestModule(pgHost = pg.host, pgPort = pg.firstMappedPort,
            pgUser = pg.username, pgPassword = pg.password,
            redisUrl = "redis://${redis.host}:${redis.firstMappedPort}")
        assertNotImplemented(client.get("/internal/quotes/SBER/history"))
    }

    @Test
    fun `unknown internal route returns 404 NOT_FOUND in unified format`() = testApplication {
        installTestModule(pgHost = pg.host, pgPort = pg.firstMappedPort,
            pgUser = pg.username, pgPassword = pg.password,
            redisUrl = "redis://${redis.host}:${redis.firstMappedPort}")
        val resp = client.get("/internal/does-not-exist")
        resp.status shouldBe HttpStatusCode.NotFound
        resp.bodyAsText() shouldContain "\"code\":\"NOT_FOUND\""
    }
}
