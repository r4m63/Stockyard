package com.stockyard.gateway.redis

import com.stockyard.gateway.config.RedisConfig
import com.stockyard.gateway.test.RedisFixture
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
        // 50 последовательных операций — пул должен корректно re-использовать соединения.
        repeat(50) {
            val result = module.withCommandConnection { conn ->
                conn.sync().set("k$it", "v$it")
                conn.sync().get("k$it")
            }
            result shouldBe "v$it"
        }
    }

    @Test
    fun `withCommandConnection returns connection to pool even on exception`() {
        // Бросаем из лямбды, проверяем что пул не «утёк» — следующий ping всё ещё работает.
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
    fun `pubSub connection is separate from command pool`() {
        val pubSub = module.pubSubConnection()
        pubSub.isOpen shouldBe true
    }

    @Test
    fun `ping returns false when Redis is unreachable`() {
        val deadModule = RedisModule(
            RedisConfig(
                url = "redis://127.0.0.1:1",         // closed port
                password = "",
            )
        )
        try {
            deadModule.ping() shouldBe false
        } finally {
            deadModule.close()
        }
    }
}
