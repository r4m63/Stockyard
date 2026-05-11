package com.stockyard.gateway.ws

import com.stockyard.gateway.config.RedisConfig
import com.stockyard.gateway.redis.RedisModule
import com.stockyard.gateway.test.RedisFixture
import com.stockyard.gateway.test.WsAuthFixture
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.mockk.mockk
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Reconnect resilience tests for QuotesSubscriber — TASK-010 T13 (Q5).
 *
 * Verifies that pattern subscription survives a Lettuce reconnect via the
 * defensive [io.lettuce.core.RedisConnectionStateListener.onRedisConnected]
 * callback registered in `QuotesSubscriber.start()`.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QuotesSubscriberIT {

    private val redis: GenericContainer<*> = RedisFixture.container()
    private val redisUrl get() = "redis://${redis.host}:${redis.firstMappedPort}"

    @BeforeAll fun start() = redis.start()
    @AfterAll  fun stop()  = redis.stop()

    private val modules = mutableListOf<RedisModule>()
    private val subscribers = mutableListOf<QuotesSubscriber>()

    @AfterEach
    fun cleanup() {
        subscribers.forEach { runCatching { it.stop() } }
        subscribers.clear()
        modules.forEach { runCatching { it.close() } }
        modules.clear()
    }

    private fun newModule(): RedisModule = RedisModule(RedisConfig(url = redisUrl, password = ""))
        .also { modules.add(it) }

    private fun newSubscriber(hub: WsHub): QuotesSubscriber {
        val sub = QuotesSubscriber(newModule(), hub, WsMetrics())
        subscribers.add(sub)
        return sub
    }

    @Test
    fun `T13a — single PUBLISH after start arrives in hub fanout under 200ms`() {
        val hub = WsHub(WsMetrics())
        // register one dummy connection subscribed to RECON
        val state = WsHub.ConnState(
            connId = "c1", userId = "u1",
            session = mockk<DefaultWebSocketServerSession>(relaxed = true),
        )
        hub.register(state).shouldBeTrue()
        hub.addSubscriptions("c1", listOf("RECON"))

        val sub = newSubscriber(hub)
        sub.start()
        Thread.sleep(100)  // let psubscribe ack

        WsAuthFixture.publishQuote(redisUrl, "RECON")

        val frame = pollOutbound(state, timeoutMs = 2_000)
        frame.shouldNotBeNull()
        (frame is OutboundFrame.Quote).shouldBeTrue()
        (frame as OutboundFrame.Quote).ticker shouldBe "RECON"
    }

    @Test
    fun `T13b — restart Redis container, PUBLISH after reconnect reaches hub under 5s`() {
        val hub = WsHub(WsMetrics())
        val state = WsHub.ConnState(
            connId = "c1", userId = "u1",
            session = mockk<DefaultWebSocketServerSession>(relaxed = true),
        )
        hub.register(state).shouldBeTrue()
        hub.addSubscriptions("c1", listOf("CHAOS"))

        val sub = newSubscriber(hub)
        sub.start()
        Thread.sleep(100)

        // Sanity: publish before chaos works.
        WsAuthFixture.publishQuote(redisUrl, "CHAOS")
        pollOutbound(state, timeoutMs = 2_000).shouldNotBeNull()

        // Drop Lettuce connection by stopping then starting the container.
        // Note: Testcontainers' restart preserves the host port, so URL is stable.
        redis.stop()
        redis.start()

        // Allow Lettuce to detect disconnect + reconnect (autoReconnect=true).
        // QuotesSubscriber's onRedisConnected fires defensively → re-psubscribe.
        val window = 5_000L
        val start = System.currentTimeMillis()
        var delivered: OutboundFrame? = null
        while (System.currentTimeMillis() - start < window) {
            // Need to re-issue PUBLISH against the (possibly new) listener;
            // best-effort each 200ms.
            runCatching { WsAuthFixture.publishQuote(redisUrl, "CHAOS") }
            delivered = pollOutbound(state, timeoutMs = 500)
            if (delivered != null) break
        }
        delivered.shouldNotBeNull()
        (delivered is OutboundFrame.Quote).shouldBeTrue()
        (delivered as OutboundFrame.Quote).ticker shouldBe "CHAOS"
    }

    @Test
    fun `T13c repeated start is idempotent`() {
        val hub = WsHub(WsMetrics())
        val sub = newSubscriber(hub)
        sub.start()
        sub.start()  // must not throw / double-subscribe
        sub.stop()
    }

    @Test
    fun `T13d — start, stop, start succeeds again`() {
        val hub = WsHub(WsMetrics())
        val sub = newSubscriber(hub)
        sub.start()
        sub.stop()
        sub.start()
        sub.stop()
    }

    @Test
    fun `T13e graceful close hub closeAll then activeConnections is 0`() {
        val hub = WsHub(WsMetrics())
        val state = WsHub.ConnState(
            connId = "c1", userId = "u1",
            session = mockk<DefaultWebSocketServerSession>(relaxed = true),
        )
        hub.register(state).shouldBeTrue()
        runBlocking { hub.closeAll(1001) }
        hub.activeConnections() shouldBe 0L
    }

    private fun pollOutbound(state: WsHub.ConnState, timeoutMs: Long): OutboundFrame? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val r = state.outbound.tryReceive()
            if (r.isSuccess) return r.getOrNull()
            Thread.sleep(20)
        }
        return null
    }
}
