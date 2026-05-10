package com.stockyard.core.redis

import com.stockyard.core.config.RedisConfig
import com.stockyard.core.test.RedisFixture
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisModuleIT {

    private val redis: GenericContainer<*> = RedisFixture.container()
    private lateinit var module: RedisModule

    @BeforeAll
    fun setUp() {
        redis.start()
        module = RedisModule(
            RedisConfig(
                url = "redis://${redis.host}:${redis.firstMappedPort}",
                password = "",
            )
        )
    }

    @AfterAll
    fun tearDown() {
        module.close()
        redis.stop()
    }

    @Test
    fun `ping returns true when Redis is up`() {
        module.ping() shouldBe true
    }

    @Test
    fun `withCommandConnection borrows and returns to pool`() {
        repeat(50) {
            val result = module.withCommandConnection { conn ->
                conn.sync().set("k$it", "v$it")
                conn.sync().get("k$it")
            }
            result shouldBe "v$it"
        }
    }

    @Test
    fun `pool returns connection to pool even on exception in lambda`() {
        try {
            module.withCommandConnection { _ ->
                error("simulated failure inside borrowed connection")
            }
        } catch (_: IllegalStateException) {
            // ожидаемо
        }
        module.ping() shouldBe true
    }

    @Test
    fun `pubSub connection is separate and open`() {
        module.pubSubConnection().isOpen shouldBe true
    }

    @Test
    fun `ping returns false when Redis unreachable`() {
        val dead = RedisModule(RedisConfig(url = "redis://127.0.0.1:1", password = ""))
        try { dead.ping() shouldBe false } finally { dead.close() }
    }
}
