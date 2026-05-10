package com.stockyard.core.test

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

/**
 * Testcontainers Redis. Образ соответствует production (redis:7-alpine).
 */
object RedisFixture {
    fun container(): GenericContainer<*> =
        GenericContainer(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--save", "", "--appendonly", "no")
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1))
}
