package com.stockyard.core.redis

import com.stockyard.core.config.RedisConfig
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
import java.time.Duration

/**
 * Lettuce-обёртка под две роли:
 *  - **command pool** (commons-pool2) для HGET текущей цены при исполнении
 *    ордера (TASK-006), а также EXISTS session:* (TASK-005).
 *  - **pub/sub connection** вне пула — не используется в core (Gateway —
 *    единственный subscriber), но создаётся для симметрии и health-check.
 *
 * Pool sizing per 12-storage-operations §12.2.3 (maxTotal=32).
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
            testOnBorrow = false
            blockWhenExhausted = true
            setMaxWait(Duration.ofMillis(500))
        }
        commandPool = ConnectionPoolSupport.createGenericObjectPool({ client.connect() }, poolConfig)
        pubSubConn = client.connectPubSub()
    }

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

    fun pubSubConnection(): StatefulRedisPubSubConnection<String, String> = pubSubConn

    override fun close() {
        runCatching { commandPool.close() }
        runCatching { pubSubConn.close() }
        runCatching { client.shutdown() }
    }
}
