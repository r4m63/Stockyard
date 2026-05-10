package com.stockyard.gateway.routing

import com.stockyard.gateway.test.RedisFixture
import com.stockyard.gateway.test.installTestModule
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
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HealthRoutesIT {

    private val redis: GenericContainer<*> = RedisFixture.container()
    private val redisUrl get() = "redis://${redis.host}:${redis.firstMappedPort}"

    @BeforeAll fun startContainers() = redis.start()
    @AfterAll  fun stopContainers()  = redis.stop()

    @Test
    fun `live returns 200 UP regardless of downstream`() = testApplication {
        installTestModule(redisUrl = redisUrl)

        val resp = client.get("/health/live")

        resp.status shouldBe HttpStatusCode.OK
        resp.bodyAsText() shouldContain "\"status\":\"UP\""
    }

    @Test
    fun `ready returns 200 when Redis is up`() = testApplication {
        installTestModule(redisUrl = redisUrl)

        val resp = client.get("/health/ready")

        resp.status shouldBe HttpStatusCode.OK
        val body = resp.bodyAsText()
        body shouldContain "\"redis\":\"UP\""
        // Core Service в этом тесте намеренно недоступен — info-only, не делает unhealthy.
        body shouldContain "\"core-service\":\"DOWN\""
    }

    @Test
    fun `ready returns 503 when Redis is down`() = testApplication {
        // Намеренно используем закрытый порт.
        installTestModule(redisUrl = "redis://127.0.0.1:1")

        val resp = client.get("/health/ready")

        resp.status shouldBe HttpStatusCode.ServiceUnavailable
        resp.bodyAsText() shouldContain "\"redis\":\"DOWN\""
    }
}
