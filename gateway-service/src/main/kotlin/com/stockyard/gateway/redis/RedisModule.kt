package com.stockyard.gateway.redis

import io.lettuce.core.ClientOptions
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.SocketOptions
import io.lettuce.core.TimeoutOptions
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import io.lettuce.core.support.ConnectionPoolSupport
import org.apache.commons.pool2.impl.GenericObjectPool
import org.apache.commons.pool2.impl.GenericObjectPoolConfig
import com.stockyard.gateway.config.RedisConfig
import java.time.Duration

/**
 * Lettuce-обёртка под две роли:
 *  - **command pool** (commons-pool2 over Lettuce) для синхронных команд:
 *    HGET, EXISTS, INCR, SET — будущие TASK'и 005/008/009;
 *  - **выделенный pub/sub connection** вне пула, один на процесс.
 *
 * Pool sizing соответствует docs/architecture/12-storage-operations.md §12.2.3
 * (maxTotal=32, maxIdle=16, minIdle=4).
 *
 * В TASK-003 используется только [ping] для readiness-check + инициализация
 * pub/sub-соединения. Реальный SUBSCRIBE — TASK-008.
 */
class RedisModule(cfg: RedisConfig) : AutoCloseable {

    private val client: RedisClient
    private val commandPool: GenericObjectPool<StatefulRedisConnection<String, String>>
    private val pubSubConn: StatefulRedisPubSubConnection<String, String>

    init {
        val uri = RedisURI.create(cfg.url).apply {
            if (cfg.password.isNotEmpty()) {
                password = cfg.password.toCharArray()
            }
            timeout = Duration.ofMillis(500)
        }

        val options = ClientOptions.builder()
            .autoReconnect(true)
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
            .timeoutOptions(TimeoutOptions.enabled(Duration.ofMillis(500)))
            .socketOptions(
                SocketOptions.builder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .keepAlive(true)
                    .build()
            )
            .build()

        client = RedisClient.create(uri).also { it.options = options }

        val poolConfig = GenericObjectPoolConfig<StatefulRedisConnection<String, String>>().apply {
            maxTotal = 32
            maxIdle = 16
            minIdle = 4
            testOnBorrow = false                 // полагаемся на autoReconnect Lettuce
            blockWhenExhausted = true
            maxWait = Duration.ofMillis(500)
        }
        commandPool = ConnectionPoolSupport.createGenericObjectPool({ client.connect() }, poolConfig)
        pubSubConn = client.connectPubSub()
    }

    /**
     * Borrow connection from command pool for one operation, then return.
     * Используется так:
     * ```
     * redis.withCommandConnection { conn -> conn.sync().exists("session:$jti") > 0 }
     * ```
     */
    fun <T> withCommandConnection(block: (StatefulRedisConnection<String, String>) -> T): T {
        val conn = commandPool.borrowObject()
        try {
            return block(conn)
        } finally {
            commandPool.returnObject(conn)
        }
    }

    fun ping(): Boolean = runCatching {
        withCommandConnection { it.sync().ping() == "PONG" }
    }.getOrElse { false }

    /** Pub/Sub-connection вне пула. См. 12-storage-operations §12.2.3. */
    fun pubSubConnection(): StatefulRedisPubSubConnection<String, String> = pubSubConn

    override fun close() {
        runCatching { commandPool.close() }
        runCatching { pubSubConn.close() }
        runCatching { client.shutdown() }
    }
}
