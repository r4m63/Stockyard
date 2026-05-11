package com.stockyard.gateway.ws

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.ChannelResult
import org.junit.jupiter.api.Test

class WsHubTest {

    private fun newHub(
        maxSubs: Int = WsHub.MAX_SUBS_PER_CONN,
        maxConns: Int = WsHub.MAX_CONNS_PER_USER,
    ): WsHub = WsHub(WsMetrics(), maxSubsPerConn = maxSubs, maxConnsPerUser = maxConns)

    private fun connState(userId: String, connId: String = "c-$userId-${counter.getAndIncrement()}"): WsHub.ConnState =
        WsHub.ConnState(connId = connId, userId = userId, session = mockk(relaxed = true))

    private val counter = java.util.concurrent.atomic.AtomicLong(0)

    // ----- register / unregister --------------------------------------------

    @Test
    fun `register returns true under cap, false at cap`() {
        val hub = newHub(maxConns = 3)
        val state1 = connState("u1"); val state2 = connState("u1"); val state3 = connState("u1")
        val state4 = connState("u1")

        hub.register(state1).shouldBeTrue()
        hub.register(state2).shouldBeTrue()
        hub.register(state3).shouldBeTrue()
        hub.register(state4).shouldBeFalse()
        hub.userConnectionCount("u1") shouldBe 3
        hub.activeConnections() shouldBe 3L
    }

    @Test
    fun `register different users independent`() {
        val hub = newHub(maxConns = 2)
        hub.register(connState("u1")).shouldBeTrue()
        hub.register(connState("u1")).shouldBeTrue()
        hub.register(connState("u2")).shouldBeTrue()
        hub.register(connState("u2")).shouldBeTrue()
        hub.userConnectionCount("u1") shouldBe 2
        hub.userConnectionCount("u2") shouldBe 2
    }

    @Test
    fun `unregister cleans all three indexes`() {
        val hub = newHub()
        val state = connState("u1", "c1")
        hub.register(state).shouldBeTrue()
        hub.addSubscriptions("c1", listOf("SBER", "GAZP"))

        hub.unregister("c1")

        hub.activeConnections() shouldBe 0L
        hub.userConnectionCount("u1") shouldBe 0
        hub.connectionsFor("SBER").shouldHaveSize(0)
        hub.connectionsFor("GAZP").shouldHaveSize(0)
    }

    @Test
    fun `unregister unknown id is no-op`() {
        val hub = newHub()
        hub.unregister("does-not-exist")  // must not throw
        hub.activeConnections() shouldBe 0L
    }

    @Test
    fun `unregister frees slot for that user`() {
        val hub = newHub(maxConns = 2)
        hub.register(connState("u1", "c1")).shouldBeTrue()
        hub.register(connState("u1", "c2")).shouldBeTrue()
        hub.register(connState("u1", "c3")).shouldBeFalse()

        hub.unregister("c1")

        hub.register(connState("u1", "c3")).shouldBeTrue()  // slot reclaimed
    }

    // ----- addSubscriptions / removeSubscriptions ---------------------------

    @Test
    fun `addSubscriptions Ok under cap`() {
        val hub = newHub()
        hub.register(connState("u1", "c1"))
        val result = hub.addSubscriptions("c1", listOf("SBER", "GAZP"))
        result.shouldBeInstanceOf<WsHub.SubscribeResult.Ok>()
        result.added shouldContainExactlyInAnyOrder listOf("SBER", "GAZP")
        hub.connectionsFor("SBER").shouldHaveSize(1)
        hub.connectionsFor("GAZP").shouldHaveSize(1)
    }

    @Test
    fun `addSubscriptions deduplicates within request`() {
        val hub = newHub()
        hub.register(connState("u1", "c1"))
        val result = hub.addSubscriptions("c1", listOf("SBER", "SBER", "SBER"))
        result.shouldBeInstanceOf<WsHub.SubscribeResult.Ok>()
        result.added shouldContainExactlyInAnyOrder listOf("SBER")
    }

    @Test
    fun `addSubscriptions skips already-subscribed tickers — no capacity consumed`() {
        val hub = newHub(maxSubs = 2)
        hub.register(connState("u1", "c1"))
        hub.addSubscriptions("c1", listOf("SBER"))

        // re-subscribe SBER + new GAZP — both under cap
        val r1 = hub.addSubscriptions("c1", listOf("SBER", "GAZP"))
        r1.shouldBeInstanceOf<WsHub.SubscribeResult.Ok>()
        r1.added shouldContainExactlyInAnyOrder listOf("GAZP")  // SBER not re-added
    }

    @Test
    fun `addSubscriptions CapExceeded with partial accept`() {
        val hub = newHub(maxSubs = 3)
        hub.register(connState("u1", "c1"))
        hub.addSubscriptions("c1", listOf("A", "B"))

        val r = hub.addSubscriptions("c1", listOf("C", "D", "E"))
        r.shouldBeInstanceOf<WsHub.SubscribeResult.CapExceeded>()
        r.accepted.shouldHaveSize(1)  // capacity = 3 - 2 = 1
        r.accepted[0] shouldBe "C"     // first to fit
    }

    @Test
    fun `addSubscriptions CapExceeded with empty accept when full`() {
        val hub = newHub(maxSubs = 2)
        hub.register(connState("u1", "c1"))
        hub.addSubscriptions("c1", listOf("A", "B"))

        val r = hub.addSubscriptions("c1", listOf("C"))
        r.shouldBeInstanceOf<WsHub.SubscribeResult.CapExceeded>()
        r.accepted.shouldHaveSize(0)
    }

    @Test
    fun `removeSubscriptions returns actually-removed list`() {
        val hub = newHub()
        hub.register(connState("u1", "c1"))
        hub.addSubscriptions("c1", listOf("A", "B", "C"))

        val removed = hub.removeSubscriptions("c1", listOf("B", "D"))  // D not subscribed
        removed shouldContainExactlyInAnyOrder listOf("B")
        hub.connectionsFor("B").shouldHaveSize(0)
        hub.connectionsFor("A").shouldHaveSize(1)
        hub.connectionsFor("C").shouldHaveSize(1)
    }

    @Test
    fun `removeSubscriptions on unknown conn returns empty`() {
        val hub = newHub()
        hub.removeSubscriptions("nope", listOf("A")) shouldBe emptyList()
    }

    // ----- connectionsFor (fanout hot path) ---------------------------------

    @Test
    fun `connectionsFor returns all subscribers for ticker`() {
        val hub = newHub()
        val s1 = connState("u1", "c1"); val s2 = connState("u2", "c2"); val s3 = connState("u3", "c3")
        listOf(s1, s2, s3).forEach { hub.register(it) }
        hub.addSubscriptions("c1", listOf("SBER"))
        hub.addSubscriptions("c2", listOf("SBER"))
        hub.addSubscriptions("c3", listOf("GAZP"))

        hub.connectionsFor("SBER").map { it.connId } shouldContainExactlyInAnyOrder listOf("c1", "c2")
        hub.connectionsFor("GAZP").map { it.connId } shouldContainExactlyInAnyOrder listOf("c3")
        hub.connectionsFor("UNKNOWN").shouldHaveSize(0)
    }

    // ----- outbound DROP_OLDEST backpressure --------------------------------

    @Test
    fun `outbound channel trySend always succeeds under DROP_OLDEST`() {
        val state = connState("u1", "c1")
        // Saturate the buffer well past capacity.
        repeat(WsHub.OUTBOUND_BUFFER * 4) { i ->
            val result: ChannelResult<Unit> = state.outbound.trySend(OutboundFrame.Error("X", "msg$i"))
            result.isSuccess.shouldBeTrue()
        }
        // Buffer must hold no more than its capacity.
        var drained = 0
        while (state.outbound.tryReceive().isSuccess) drained++
        drained shouldBe WsHub.OUTBOUND_BUFFER
    }

    @Test
    fun `closeAll on empty hub is no-op`() {
        val hub = newHub()
        kotlinx.coroutines.runBlocking { hub.closeAll(1001) }
        hub.activeConnections() shouldBe 0L
    }

    // ----- concurrency stress ----------------------------------------------

    @Test
    fun `concurrent subscribe and unsubscribe — indexes stay consistent`() {
        val hub = newHub(maxSubs = 1000)
        repeat(10) { i -> hub.register(connState("u$i", "c$i")) }

        val tickers = (1..200).map { "T$it" }
        val pool = Executors.newFixedThreadPool(20)
        val latch = CountDownLatch(20)

        // 10 threads subscribe + 10 threads unsubscribe
        repeat(10) { workerIdx ->
            pool.submit {
                try {
                    val connId = "c${workerIdx}"
                    repeat(200) { iter ->
                        val pick = tickers.shuffled().take(50)
                        hub.addSubscriptions(connId, pick)
                        hub.removeSubscriptions(connId, pick.take(25))
                    }
                } finally { latch.countDown() }
            }
        }
        repeat(10) { workerIdx ->
            pool.submit {
                try {
                    val connId = "c${workerIdx}"
                    repeat(200) {
                        hub.removeSubscriptions(connId, tickers.shuffled().take(40))
                    }
                } finally { latch.countDown() }
            }
        }
        latch.await(30, TimeUnit.SECONDS).shouldBeTrue()
        pool.shutdown()

        // Invariant: for every ticker, byTicker[t] set matches set of conns
        // whose state.tickers contains t. We assert reverse direction by
        // iterating connectionsFor(t) and checking membership.
        tickers.forEach { ticker ->
            hub.connectionsFor(ticker).forEach { state ->
                state.tickers.contains(ticker).shouldBeTrue()
            }
        }
        hub.activeConnections() shouldBe 10L
    }

    @Test
    fun `concurrent register up to cap exactly`() {
        val hub = newHub(maxConns = 5)
        val pool = Executors.newFixedThreadPool(20)
        val accepted = java.util.concurrent.atomic.AtomicInteger(0)
        val latch = CountDownLatch(20)

        repeat(20) {
            pool.submit {
                try {
                    if (hub.register(connState("u1"))) accepted.incrementAndGet()
                } finally { latch.countDown() }
            }
        }
        latch.await(10, TimeUnit.SECONDS).shouldBeTrue()
        pool.shutdown()

        // Strict equality — per-user cap must be exact under contention.
        accepted.get() shouldBe 5
        hub.userConnectionCount("u1") shouldBe 5
        hub.activeConnections() shouldBeGreaterThanOrEqual 5L
    }
}
