package com.stockyard.gateway

import com.stockyard.gateway.test.RedisFixture
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Verifies reviewer round 1 finding **H1**: gateway must fail fast on
 * empty/short JWT_SECRET, не молча падать в insecure-default.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApplicationStartupIT {

    private val redis: GenericContainer<*> = RedisFixture.container()
    private val redisUrl get() = "redis://${redis.host}:${redis.firstMappedPort}"

    @BeforeAll fun start() = redis.start()
    @AfterAll  fun stop()  = redis.stop()

    private fun testConfig(secret: String) = MapApplicationConfig(
        "stockyard.jwt.secret" to secret,
        "stockyard.jwt.issuer" to "stockyard-gateway",
        "stockyard.jwt.audience" to "stockyard-clients",
        "stockyard.jwt.accessTtlSeconds" to "900",
        "stockyard.jwt.refreshTtlSeconds" to "2592000",
        "stockyard.redis.url" to redisUrl,
        "stockyard.redis.password" to "",
        "stockyard.coreService.baseUrl" to "http://localhost:1",
        "stockyard.coreService.connectTimeoutMs" to "200",
        "stockyard.coreService.requestTimeoutMs" to "500",
        "stockyard.otel.serviceName" to "gateway-service-test",
        "stockyard.otel.otlpEndpoint" to "http://localhost:14317",
    )

    @Test
    fun `startup fails fast when JWT_SECRET is empty`() {
        val ex = shouldThrow<IllegalArgumentException> {
            testApplication {
                environment { config = testConfig(secret = "") }
                application { module() }
                // module() выполняется лениво — триггерим хоть один запрос,
                // чтобы require() выкинул IllegalArgumentException.
                client.get("/health/live")
            }
        }
        ex.message!! shouldContain "JWT_SECRET must be at least 32 characters"
    }

    @Test
    fun `startup fails fast when JWT_SECRET is shorter than 32 chars`() {
        val ex = shouldThrow<IllegalArgumentException> {
            testApplication {
                environment { config = testConfig(secret = "too-short") }
                application { module() }
                client.get("/health/live")
            }
        }
        ex.message!! shouldContain "32 characters"
    }

    @Test
    fun `startup succeeds with adequate JWT_SECRET`() = testApplication {
        environment {
            config = testConfig(secret = "this-is-a-test-secret-32-bytes-min-length")
        }
        application { module() }

        // Если require() не выбросил — health/live ответит 200.
        val resp = client.get("/health/live")
        // Если бы startup упал — здесь полетел бы exception раньше, тест и assertion излишни.
        check(resp.status.value == 200) { "expected 200, got ${resp.status}" }
    }
}
