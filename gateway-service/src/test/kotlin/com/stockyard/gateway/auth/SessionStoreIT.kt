package com.stockyard.gateway.auth

import com.stockyard.gateway.config.RedisConfig
import com.stockyard.gateway.redis.RedisModule
import com.stockyard.gateway.test.RedisFixture
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * IT для [SessionStore] на реальном Redis (Testcontainers).
 * Проверяет ключи `session:{jti}` и `refresh:{jti}` + TTL + DEL.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionStoreIT {

    private val redis: GenericContainer<*> = RedisFixture.container()
    private lateinit var module: RedisModule
    private lateinit var store: SessionStore

    @BeforeAll
    fun setUp() {
        redis.start()
        module = RedisModule(RedisConfig(url = "redis://${redis.host}:${redis.firstMappedPort}", password = ""))
        store = SessionStore(module)
    }

    @AfterAll
    fun tearDown() {
        module.close()
        redis.stop()
    }

    @Test
    fun `storeAccessSession then accessSessionExists returns true`() {
        store.storeAccessSession("jti-a-1", "u_test", 900)
        store.accessSessionExists("jti-a-1") shouldBe true
    }

    @Test
    fun `storeRefreshSession then refreshSessionExists returns true`() {
        store.storeRefreshSession("jti-r-1", "u_test", 2_592_000)
        store.refreshSessionExists("jti-r-1") shouldBe true
    }

    @Test
    fun `unknown jti returns false`() {
        store.accessSessionExists("jti-never-stored") shouldBe false
        store.refreshSessionExists("jti-never-stored") shouldBe false
    }

    @Test
    fun `deleteRefreshSession removes the key`() {
        store.storeRefreshSession("jti-r-del", "u_test", 600)
        store.refreshSessionExists("jti-r-del") shouldBe true
        store.deleteRefreshSession("jti-r-del")
        store.refreshSessionExists("jti-r-del") shouldBe false
    }

    @Test
    fun `deleteRefreshSession is idempotent on missing key`() {
        // Не должен бросать, даже если ключа уже нет.
        store.deleteRefreshSession("jti-never-existed")
        store.refreshSessionExists("jti-never-existed") shouldBe false
    }

    @Test
    fun `access and refresh use separate keyspaces`() {
        // Если бы ключи пересекались, store с одним jti дал бы exists на оба.
        store.storeAccessSession("shared-jti", "u_test", 900)
        store.accessSessionExists("shared-jti") shouldBe true
        store.refreshSessionExists("shared-jti") shouldBe false

        store.storeRefreshSession("shared-jti", "u_test", 900)
        store.accessSessionExists("shared-jti") shouldBe true
        store.refreshSessionExists("shared-jti") shouldBe true
    }

    @Test
    fun `stored value contains userId for lookup`() {
        // Прямая проверка через Lettuce: убедиться, что value записан как userId.
        store.storeAccessSession("jti-with-uid", "u_specific123", 900)
        val raw = module.withCommandConnection { it.sync().get("session:jti-with-uid") }
        raw shouldBe "u_specific123"
    }

    @Test
    fun `TTL is applied to access session`() {
        store.storeAccessSession("jti-ttl-a", "u_test", 60)
        val ttl = module.withCommandConnection { it.sync().ttl("session:jti-ttl-a") }
        // TTL может быть от 58 до 60 (мс на сетап) — главное, что > 0 и <= 60.
        (ttl in 1..60) shouldBe true
    }

    @Test
    fun `TTL is applied to refresh session`() {
        store.storeRefreshSession("jti-ttl-r", "u_test", 120)
        val ttl = module.withCommandConnection { it.sync().ttl("refresh:jti-ttl-r") }
        (ttl in 1..120) shouldBe true
    }
}
