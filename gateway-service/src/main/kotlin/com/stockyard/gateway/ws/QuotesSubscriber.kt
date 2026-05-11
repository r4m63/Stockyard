package com.stockyard.gateway.ws

import com.stockyard.gateway.redis.RedisModule
import io.lettuce.core.RedisChannelHandler
import io.lettuce.core.RedisConnectionStateListener
import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.RedisPubSubListener
import java.net.SocketAddress
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Bridges Redis Pub/Sub (`channel:quotes:*`) into the in-process WS fanout.
 *
 * Wraps the singleton [io.lettuce.core.pubsub.StatefulRedisPubSubConnection]
 * exposed by [RedisModule] — does not own a [io.lettuce.core.RedisClient]. A
 * single pattern subscription (ADR-013) feeds the in-memory reverse index in
 * [WsHub] and scales O(subscribers_for_ticker) per tick.
 *
 * **Reconnect resilience (TASK-010 Q5).** Lettuce 6.x does NOT automatically
 * re-issue pattern subscriptions after a transport reconnect — the local
 * `autoReconnect=true` only restores the TCP connection. We register a
 * [RedisConnectionStateListener] via [RedisModule.addConnectionStateListener]
 * that defensively re-`psubscribe`s on every `onRedisConnected` event. The
 * call is idempotent (Redis tolerates duplicate PSUBSCRIBE), so the cost is a
 * single round-trip per reconnect — see T13 chaos test.
 *
 * Decode happens on Lettuce's I/O thread (CPU-cheap at the ≤2500 msg/s
 * absolute ceiling). If profiling later shows contention, dispatch can move
 * to `Dispatchers.Default` via a launch per message — do **not** optimize
 * preemptively.
 */
class QuotesSubscriber(
    private val redis: RedisModule,
    private val hub: WsHub,
    private val metrics: WsMetrics,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val pubSub = redis.pubSubConnection()

    private val messageListener: RedisPubSubListener<String, String> =
        object : RedisPubSubAdapter<String, String>() {
            override fun message(pattern: String, channel: String, message: String) {
                metrics.pubsubMessages.add(1)
                val ticker = channel.removePrefix(CHANNEL_PREFIX)
                if (ticker == channel || ticker.isEmpty()) return
                val frame = decodeQuote(json, message)
                if (frame == null) {
                    metrics.pubsubParseErrors.add(1)
                    log.atDebug()
                        .addKeyValue("channel", channel)
                        .log("pubsub payload decode failed")
                    return
                }
                hub.connectionsFor(ticker).forEach { state ->
                    if (state.outbound.trySend(frame).isFailure) {
                        metrics.framesDropped.add(1, metrics.typeAttrs(frame.typeLabel()))
                    }
                }
            }
        }

    private val stateListener: RedisConnectionStateListener =
        object : RedisConnectionStateListener {
            override fun onRedisConnected(
                connection: RedisChannelHandler<*, *>,
                socketAddress: SocketAddress?,
            ) {
                if (!started) return
                runCatching {
                    pubSub.async().psubscribe(CHANNEL_PATTERN).toCompletableFuture()
                        .get(SUBSCRIBE_TIMEOUT_SEC, TimeUnit.SECONDS)
                }.onFailure { e ->
                    log.atWarn()
                        .addKeyValue("pattern", CHANNEL_PATTERN)
                        .log("re-psubscribe after reconnect failed: {}", e.message)
                }
                log.atInfo()
                    .addKeyValue("pattern", CHANNEL_PATTERN)
                    .log("re-psubscribe after reconnect")
            }

            override fun onRedisDisconnected(connection: RedisChannelHandler<*, *>) {
                log.atInfo().log("Redis disconnected; will re-psubscribe on reconnect")
            }

            override fun onRedisExceptionCaught(
                connection: RedisChannelHandler<*, *>,
                cause: Throwable,
            ) {
                log.atDebug().log("Redis exception: {}", cause.message)
            }
        }

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        pubSub.addListener(messageListener)
        redis.addConnectionStateListener(stateListener)
        pubSub.async().psubscribe(CHANNEL_PATTERN).toCompletableFuture()
            .get(SUBSCRIBE_TIMEOUT_SEC, TimeUnit.SECONDS)
        started = true
        log.atInfo().addKeyValue("pattern", CHANNEL_PATTERN).log("QuotesSubscriber started")
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching {
            pubSub.async().punsubscribe(CHANNEL_PATTERN).toCompletableFuture()
                .get(SUBSCRIBE_TIMEOUT_SEC, TimeUnit.SECONDS)
        }
        runCatching { pubSub.removeListener(messageListener) }
        log.info("QuotesSubscriber stopped")
    }

    companion object {
        private const val CHANNEL_PREFIX = "channel:quotes:"
        private const val CHANNEL_PATTERN = "channel:quotes:*"
        private const val SUBSCRIBE_TIMEOUT_SEC = 2L
    }
}
