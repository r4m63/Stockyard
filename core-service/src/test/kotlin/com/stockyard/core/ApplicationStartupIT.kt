package com.stockyard.core

import com.stockyard.core.test.PgFixture
import com.stockyard.core.test.RedisFixture
import com.stockyard.core.test.installTestModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Verifies fail-fast валидация секретов (по аналогии с TASK-003 H1):
 * Application.module() должен бросать IllegalArgumentException на:
 *  - пустом/коротком ARGON2_PEPPER,
 *  - пустом PG_PASSWORD.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApplicationStartupIT {

    private val pg: PostgreSQLContainer<*> = PgFixture.container()
    private val redis: GenericContainer<*> = RedisFixture.container()

    @BeforeAll fun start() { pg.start(); redis.start() }
    @AfterAll  fun stop()  { pg.stop(); redis.stop() }

    private val redisUrl get() = "redis://${redis.host}:${redis.firstMappedPort}"

    @Test
    fun `startup fails fast when ARGON2_PEPPER is empty`() {
        val ex = shouldThrow<IllegalArgumentException> {
            testApplication {
                installTestModule(
                    pgHost = pg.host, pgPort = pg.firstMappedPort,
                    pgUser = pg.username, pgPassword = pg.password,
                    redisUrl = redisUrl,
                    argon2Pepper = "",
                )
                client.get("/health/live")    // триггер lazy module() init
            }
        }
        ex.message!! shouldContain "ARGON2_PEPPER must be at least 32 bytes"
    }

    @Test
    fun `startup fails fast when ARGON2_PEPPER is shorter than 32 bytes`() {
        val ex = shouldThrow<IllegalArgumentException> {
            testApplication {
                installTestModule(
                    pgHost = pg.host, pgPort = pg.firstMappedPort,
                    pgUser = pg.username, pgPassword = pg.password,
                    redisUrl = redisUrl,
                    argon2Pepper = "too-short",
                )
                client.get("/health/live")
            }
        }
        ex.message!! shouldContain "32 bytes"
    }

    @Test
    fun `startup fails fast when PG_PASSWORD is empty`() {
        val ex = shouldThrow<IllegalArgumentException> {
            testApplication {
                installTestModule(
                    pgHost = pg.host, pgPort = pg.firstMappedPort,
                    pgUser = pg.username, pgPassword = "",     // пустой пароль
                    redisUrl = redisUrl,
                )
                client.get("/health/live")
            }
        }
        ex.message!! shouldContain "PG_PASSWORD"
    }

    @Test
    fun `startup succeeds with valid secrets`() = testApplication {
        installTestModule(
            pgHost = pg.host, pgPort = pg.firstMappedPort,
            pgUser = pg.username, pgPassword = pg.password,
            redisUrl = redisUrl,
        )
        val resp = client.get("/health/live")
        check(resp.status.value == 200) { "expected 200, got ${resp.status}" }
    }
}
