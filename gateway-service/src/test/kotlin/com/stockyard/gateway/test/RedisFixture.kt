package com.stockyard.gateway.test

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

/**
 * Testcontainers-обёртка над redis:7-alpine для интеграционных тестов.
 *
 * Использование:
 * ```
 * @Testcontainers
 * class MyIT {
 *     companion object {
 *         @Container
 *         @JvmStatic val redis = RedisFixture.container()
 *     }
 *     val redisUrl get() = "redis://${redis.host}:${redis.firstMappedPort}"
 * }
 * ```
 */
object RedisFixture {
    fun container(): GenericContainer<*> =
        GenericContainer(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--save", "", "--appendonly", "no")
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1))
}
