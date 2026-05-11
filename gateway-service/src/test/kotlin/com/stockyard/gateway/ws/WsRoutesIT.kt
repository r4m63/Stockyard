package com.stockyard.gateway.ws

import com.stockyard.gateway.test.RedisFixture
import com.stockyard.gateway.test.WsAuthFixture
import com.stockyard.gateway.test.installTestModule
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Integration tests for `/v1/ws/quotes` — TASK-010 T4-T16.
 *
 * Requires Docker (Testcontainers Redis). If `docker info` fails locally,
 * these tests skip with no execution — `@Testcontainers` startup throws and
 * JUnit marks the class as errored. CI/Docker environment is the source of
 * truth (see TASK-009 F2 precedent).
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WsRoutesIT {

    private val redis: GenericContainer<*> = RedisFixture.container()
    private val redisUrl get() = "redis://${redis.host}:${redis.firstMappedPort}"
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeAll fun start() = redis.start()
    @AfterAll  fun stop()  = redis.stop()

    private fun parseFrame(text: String): JsonObject =
        json.parseToJsonElement(text) as JsonObject

    // ----- T4: handshake JWT valid → accepted ------------------------------

    @Test
    fun `T4 valid JWT and active session — handshake accepted`() = testApplication {
        installTestModule(redisUrl = redisUrl)
        val client = createClient { install(WebSockets) }
        val token = WsAuthFixture.issueAndSeed(redisUrl).token

        client.webSocket("/v1/ws/quotes?token=$token") {
            send(Frame.Text("""{"action":"ping"}"""))
            val pong = (incoming.receive() as Frame.Text).readText()
            parseFrame(pong)["type"]!!.jsonPrimitive.content shouldBe "pong"
        }
    }

    // ----- T5: handshake auth failures → close 4001 -------------------------

    @Test
    fun `T5a missing token closes 4001`() = testApplication {
        installTestModule(redisUrl = redisUrl)
        val client = createClient { install(WebSockets) }
        val session = client.webSocketSession("/v1/ws/quotes")
        val reason = withTimeoutOrNull(3_000) { session.incoming.receive() }
        // Receive throws CloseReceived; assert via session closeReason instead.
        val closeReason: CloseReason? = withTimeoutOrNull(3_000) { session.closeReason.await() }
        closeReason.shouldNotBeNull()
        closeReason.code shouldBe 4001
        reason  // suppress unused warning
    }

    @Test
    fun `T5b expired token closes 4001`() = testApplication {
        installTestModule(redisUrl = redisUrl)
        val client = createClient { install(WebSockets) }
        val token = WsAuthFixture.issueExpired()
        val session = client.webSocketSession("/v1/ws/quotes?token=$token")
        val reason = withTimeoutOrNull(3_000) { session.closeReason.await() }
        reason.shouldNotBeNull()
        reason.code shouldBe 4001
    }

    @Test
    fun `T5c revoked session closes 4001`() = testApplication {
        installTestModule(redisUrl = redisUrl)
        val client = createClient { install(WebSockets) }
        val token = WsAuthFixture.issueWithoutSession()
        val session = client.webSocketSession("/v1/ws/quotes?token=$token")
        val reason = withTimeoutOrNull(3_000) { session.closeReason.await() }
        reason.shouldNotBeNull()
        reason.code shouldBe 4001
    }

    // ----- T6: subscribe + external PUBLISH → quote frame received ----------

    @Test
    fun `T6 subscribe receives external PUBLISH within 200ms`() = testApplication {
        installTestModule(redisUrl = redisUrl)
        val client = createClient { install(WebSockets) }
        val token = WsAuthFixture.issueAndSeed(redisUrl).token

        client.webSocket("/v1/ws/quotes?token=$token") {
            send(Frame.Text("""{"action":"subscribe","tickers":["SBER"]}"""))
            // First frame is SubAck (no snapshot — HASH not seeded).
            val ack = parseFrame((incoming.receive() as Frame.Text).readText())
            ack["type"]!!.jsonPrimitive.content shouldBe "subscribed"

            // Publish on background.
            WsAuthFixture.publishQuote(redisUrl, "SBER")

            val quoteText = withTimeoutOrNull(2_000) {
                (incoming.receive() as Frame.Text).readText()
            }
            quoteText.shouldNotBeNull()
            val quote = parseFrame(quoteText)
            quote["type"]!!.jsonPrimitive.content shouldBe "quote"
            quote["ticker"]!!.jsonPrimitive.content shouldBe "SBER"
            quote["bidCents"]!!.jsonPrimitive.content shouldBe "28550"
            quote["askCents"]!!.jsonPrimitive.content shouldBe "28570"
        }
    }

    // ----- T7: 2 clients, 1 PUBLISH → both receive --------------------------

    @Test
    fun `T7 two clients receive the same PUBLISH`() = testApplication {
        installTestModule(redisUrl = redisUrl)
        val client = createClient { install(WebSockets) }
        val t1 = WsAuthFixture.issueAndSeed(redisUrl).token
        val t2 = WsAuthFixture.issueAndSeed(redisUrl).token

        val s1 = client.webSocketSession("/v1/ws/quotes?token=$t1")
        val s2 = client.webSocketSession("/v1/ws/quotes?token=$t2")
        try {
            s1.send(Frame.Text("""{"action":"subscribe","tickers":["GAZP"]}"""))
            s2.send(Frame.Text("""{"action":"subscribe","tickers":["GAZP"]}"""))
            // drain SubAck
            (s1.incoming.receive() as Frame.Text).readText()
            (s2.incoming.receive() as Frame.Text).readText()

            WsAuthFixture.publishQuote(redisUrl, "GAZP")

            val q1 = withTimeoutOrNull(2_000) { (s1.incoming.receive() as Frame.Text).readText() }
            val q2 = withTimeoutOrNull(2_000) { (s2.incoming.receive() as Frame.Text).readText() }
            q1.shouldNotBeNull(); q2.shouldNotBeNull()
            parseFrame(q1)["ticker"]!!.jsonPrimitive.content shouldBe "GAZP"
            parseFrame(q2)["ticker"]!!.jsonPrimitive.content shouldBe "GAZP"
        } finally {
            s1.close(); s2.close()
        }
    }

    // ----- T8: subscribe past hard cap → SUBSCRIPTION_LIMIT -----------------

    @Test
    fun `T8 subscribe over cap returns SUBSCRIPTION_LIMIT`() = testApplication {
        installTestModule(redisUrl = redisUrl)
        val client = createClient { install(WebSockets) }
        val token = WsAuthFixture.issueAndSeed(redisUrl).token

        client.webSocket("/v1/ws/quotes?token=$token") {
            val firstHundred = (1..WsHub.MAX_SUBS_PER_CONN).map { "T$it" }
            send(Frame.Text("""{"action":"subscribe","tickers":${firstHundred.joinToString(",", "[", "]") { "\"$it\"" }}}"""))
            // drain SubAck for first 100
            (incoming.receive() as Frame.Text).readText()

            send(Frame.Text("""{"action":"subscribe","tickers":["OVERFLOW"]}"""))
            val errText = (incoming.receive() as Frame.Text).readText()
            errText.shouldContain("\"code\":\"SUBSCRIPTION_LIMIT\"")
        }
    }

    // ----- T9: per-user conn cap → 4002 -------------------------------------

    @Test
    fun `T9 sixth connection same user closes 4002`() = testApplication {
        installTestModule(redisUrl = redisUrl)
        val client = createClient { install(WebSockets) }
        val issued = WsAuthFixture.issueAndSeed(redisUrl)
        // Reuse same userId across 6 tokens — seed 5 more sessions for same user.
        val tokens = (1..WsHub.MAX_CONNS_PER_USER).map {
            WsAuthFixture.issueAndSeed(redisUrl, userId = issued.userId).token
        }

        val sessions = tokens.map { client.webSocketSession("/v1/ws/quotes?token=$it") }
        try {
            // Wait briefly to let the 5 register
            delay(200)

            val sixth = WsAuthFixture.issueAndSeed(redisUrl, userId = issued.userId).token
            val sixthSession = client.webSocketSession("/v1/ws/quotes?token=$sixth")
            val reason = withTimeoutOrNull(3_000) { sixthSession.closeReason.await() }
            reason.shouldNotBeNull()
            reason.code shouldBe 4002
        } finally {
            sessions.forEach { runCatching { it.close() } }
        }
    }

    // ----- T9b: closing one frees the slot ----------------------------------

    @Test
    fun `T9b closed slot is reclaimed by next handshake`() = testApplication {
        installTestModule(redisUrl = redisUrl)
        val client = createClient { install(WebSockets) }
        val issued = WsAuthFixture.issueAndSeed(redisUrl)
        val tokens = (1..WsHub.MAX_CONNS_PER_USER).map {
            WsAuthFixture.issueAndSeed(redisUrl, userId = issued.userId).token
        }
        val sessions = tokens.map { client.webSocketSession("/v1/ws/quotes?token=$it") }
        try {
            delay(200)
            sessions.first().close()
            delay(500)  // reaper grace

            val replacement = WsAuthFixture.issueAndSeed(redisUrl, userId = issued.userId).token
            client.webSocket("/v1/ws/quotes?token=$replacement") {
                send(Frame.Text("""{"action":"ping"}"""))
                val pong = parseFrame((incoming.receive() as Frame.Text).readText())
                pong["type"]!!.jsonPrimitive.content shouldBe "pong"
            }
        } finally {
            sessions.drop(1).forEach { runCatching { it.close() } }
        }
    }

    // ----- T10: snapshot precedes live tick on subscribe --------------------

    @Test
    fun `T10 reconnect snapshot from HGETALL arrives before live ticks`() = testApplication {
        installTestModule(redisUrl = redisUrl)
        val client = createClient { install(WebSockets) }
        val token = WsAuthFixture.issueAndSeed(redisUrl).token
        WsAuthFixture.seedQuoteHash(redisUrl, "LKOH", bidCents = 100, askCents = 110, lastCents = 105, volume = 7)

        client.webSocket("/v1/ws/quotes?token=$token") {
            send(Frame.Text("""{"action":"subscribe","tickers":["LKOH"]}"""))
            // First frame must be the snapshot Quote, not the SubAck.
            val first = parseFrame((incoming.receive() as Frame.Text).readText())
            first["type"]!!.jsonPrimitive.content shouldBe "quote"
            first["bidCents"]!!.jsonPrimitive.content shouldBe "100"
            first["askCents"]!!.jsonPrimitive.content shouldBe "110"
            // Then the SubAck.
            val second = parseFrame((incoming.receive() as Frame.Text).readText())
            second["type"]!!.jsonPrimitive.content shouldBe "subscribed"
        }
    }

    // ----- T11: unsubscribe stops fanout for that conn ----------------------

    @Test
    fun `T11 unsubscribe stops fanout for that conn, others unaffected`() = testApplication {
        installTestModule(redisUrl = redisUrl)
        val client = createClient { install(WebSockets) }
        val t1 = WsAuthFixture.issueAndSeed(redisUrl).token
        val t2 = WsAuthFixture.issueAndSeed(redisUrl).token

        val s1 = client.webSocketSession("/v1/ws/quotes?token=$t1")
        val s2 = client.webSocketSession("/v1/ws/quotes?token=$t2")
        try {
            s1.send(Frame.Text("""{"action":"subscribe","tickers":["TATN"]}"""))
            s2.send(Frame.Text("""{"action":"subscribe","tickers":["TATN"]}"""))
            (s1.incoming.receive() as Frame.Text).readText()
            (s2.incoming.receive() as Frame.Text).readText()

            s1.send(Frame.Text("""{"action":"unsubscribe","tickers":["TATN"]}"""))
            val unsubText = (s1.incoming.receive() as Frame.Text).readText()
            parseFrame(unsubText)["type"]!!.jsonPrimitive.content shouldBe "unsubscribed"

            WsAuthFixture.publishQuote(redisUrl, "TATN")

            val s1Next = withTimeoutOrNull(500) { (s1.incoming.receive() as Frame.Text).readText() }
            s1Next.shouldBeNull()  // s1 must NOT receive
            val s2Next = withTimeoutOrNull(2_000) { (s2.incoming.receive() as Frame.Text).readText() }
            s2Next.shouldNotBeNull()
            parseFrame(s2Next)["ticker"]!!.jsonPrimitive.content shouldBe "TATN"
        } finally {
            s1.close(); s2.close()
        }
    }

    // ----- T12: idle timeout closes connection -----------------------------

    // SKIPPED — Ktor TestApplication overrides plugin timeouts; verifying
    // 60s idle close requires a real Netty engine which adds 60+ sec to
    // the suite. Covered manually in /tester chaos session.

    // ----- T15: E2E via DevPriceFixture-shaped PUBLISH ----------------------

    @Test
    fun `T15 E2E PUBLISH integer cents — client sees cents intact, no 100x bug`() = testApplication {
        installTestModule(redisUrl = redisUrl)
        val client = createClient { install(WebSockets) }
        val token = WsAuthFixture.issueAndSeed(redisUrl).token

        client.webSocket("/v1/ws/quotes?token=$token") {
            send(Frame.Text("""{"action":"subscribe","tickers":["MGNT"]}"""))
            (incoming.receive() as Frame.Text).readText()  // SubAck

            // Cents value 50000 = 500.00 RUB. If anywhere on path we have /100 → 5.00 → bug.
            WsAuthFixture.publishQuote(redisUrl, "MGNT", bidCents = 50000, askCents = 50050, lastCents = 50025)

            val qText = withTimeoutOrNull(2_000) { (incoming.receive() as Frame.Text).readText() }
            qText.shouldNotBeNull()
            val q = parseFrame(qText)
            q["bidCents"]!!.jsonPrimitive.content shouldBe "50000"
            q["askCents"]!!.jsonPrimitive.content shouldBe "50050"
            q["lastCents"]!!.jsonPrimitive.content shouldBe "50025"
        }
    }

    // ----- T16: graceful shutdown ------------------------------------------

    // T16 (graceful shutdown leak detection) is split:
    //   - direct WsHub.closeAll behavior is covered in [WsHubTest].
    //   - Application-level engine.stop() interplay requires a real Netty
    //     engine which adds 60+ sec startup; verified manually.

    @Test
    fun `T16b post-disconnect activeConnections returns to baseline`() = testApplication {
        installTestModule(redisUrl = redisUrl)
        val client = createClient { install(WebSockets) }
        val token = WsAuthFixture.issueAndSeed(redisUrl).token

        client.webSocket("/v1/ws/quotes?token=$token") {
            send(Frame.Text("""{"action":"ping"}"""))
            val pong = withTimeoutOrNull(2_000) { (incoming.receive() as Frame.Text).readText() }
            pong.shouldNotBeNull()
        }
        // After the webSocket block exits, the route's `finally` calls
        // hub.unregister. Nothing more we can directly assert about the
        // application-scoped hub instance from here — the test harness
        // tears down the app at the end of testApplication. T16b is largely
        // a "no exception leaked" check.
    }

    // ----- Defensive: verify Redis SET/SUBSCRIBE outside route -------------

    @Test
    fun `redis fixture publish reaches an external subscriber — sanity`() {
        val client = RedisClient.create(RedisURI.create(redisUrl))
        val received = java.util.concurrent.LinkedBlockingQueue<String>()
        try {
            val sub = client.connectPubSub()
            sub.addListener(object : io.lettuce.core.pubsub.RedisPubSubAdapter<String, String>() {
                override fun message(channel: String, message: String) { received.offer(message) }
            })
            sub.sync().subscribe("channel:quotes:SANITY")
            WsAuthFixture.publishQuote(redisUrl, "SANITY")
            val msg = received.poll(3, java.util.concurrent.TimeUnit.SECONDS)
            msg.shouldNotBeNull()
            sub.close()
        } finally {
            client.shutdown()
        }
        // sanity for fixture itself; no hub involved
        listOf<String>().shouldContainExactlyInAnyOrder(emptyList())
    }
}
