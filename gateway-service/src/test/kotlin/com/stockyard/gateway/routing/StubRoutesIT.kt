package com.stockyard.gateway.routing

import com.stockyard.gateway.test.RedisFixture
import com.stockyard.gateway.test.installTestModule
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Проверяет, что оставшиеся stub-эндпоинты (`/v1/orders`, `/v1/portfolio`,
 * `/v1/instruments`, `/v1/quotes/*`) ещё возвращают 501 NOT_IMPLEMENTED.
 *
 * `/v1/auth/{register,login,refresh}` стали реальными в TASK-005 — их IT в
 * [com.stockyard.gateway.routing.AuthRoutesIT].
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StubRoutesIT {

    private val redis: GenericContainer<*> = RedisFixture.container()
    private val redisUrl get() = "redis://${redis.host}:${redis.firstMappedPort}"

    @BeforeAll fun start() = redis.start()
    @AfterAll  fun stop()  = redis.stop()

    private suspend fun assertNotImplemented(body: String, code: HttpStatusCode) {
        code shouldBe HttpStatusCode.NotImplemented
        body shouldContain "\"error\""
        body shouldContain "\"code\":\"NOT_IMPLEMENTED\""
    }

    @Test
    fun `POST v1 orders returns 501`() = testApplication {
        installTestModule(redisUrl = redisUrl)
        val resp = client.post("/v1/orders")
        assertNotImplemented(resp.bodyAsText(), resp.status)
    }

    @Test
    fun `GET v1 orders returns 501`() = testApplication {
        installTestModule(redisUrl = redisUrl)
        val resp = client.get("/v1/orders")
        assertNotImplemented(resp.bodyAsText(), resp.status)
    }

    @Test
    fun `GET v1 portfolio returns 501`() = testApplication {
        installTestModule(redisUrl = redisUrl)
        val resp = client.get("/v1/portfolio")
        assertNotImplemented(resp.bodyAsText(), resp.status)
    }

    @Test
    fun `GET v1 instruments returns 501`() = testApplication {
        installTestModule(redisUrl = redisUrl)
        val resp = client.get("/v1/instruments")
        assertNotImplemented(resp.bodyAsText(), resp.status)
    }

    @Test
    fun `GET v1 quotes ticker returns 501`() = testApplication {
        installTestModule(redisUrl = redisUrl)
        val resp = client.get("/v1/quotes/SBER")
        assertNotImplemented(resp.bodyAsText(), resp.status)
    }

    @Test
    fun `GET v1 quotes ticker history returns 501`() = testApplication {
        installTestModule(redisUrl = redisUrl)
        val resp = client.get("/v1/quotes/SBER/history")
        assertNotImplemented(resp.bodyAsText(), resp.status)
    }

    @Test
    fun `unknown route returns 404 in unified error format`() = testApplication {
        installTestModule(redisUrl = redisUrl)
        val resp = client.get("/v1/does-not-exist")

        resp.status shouldBe HttpStatusCode.NotFound
        resp.bodyAsText() shouldContain "\"code\":\"NOT_FOUND\""
    }
}
