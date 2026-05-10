package com.stockyard.gateway.ws

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.ktor.websocket.WebSocketSession
import io.mockk.mockk
import org.junit.jupiter.api.Test

class WsHubTest {

    @Test
    fun `add and remove session`() {
        val hub = WsHub()
        val session = mockk<WebSocketSession>()

        hub.activeSessions() shouldBe 0
        hub.add(session)
        hub.activeSessions() shouldBe 1

        hub.remove(session)
        hub.activeSessions() shouldBe 0
    }

    @Test
    fun `subscribe accumulates tickers`() {
        val hub = WsHub()
        val session = mockk<WebSocketSession>()
        hub.add(session)

        hub.subscribe(session, listOf("SBER"))
        hub.subscribe(session, listOf("GAZP", "LKOH"))

        hub.subscriptions(session) shouldContainExactlyInAnyOrder listOf("SBER", "GAZP", "LKOH")
    }

    @Test
    fun `subscribe deduplicates`() {
        val hub = WsHub()
        val session = mockk<WebSocketSession>()
        hub.add(session)

        hub.subscribe(session, listOf("SBER"))
        hub.subscribe(session, listOf("SBER", "SBER"))

        hub.subscriptions(session) shouldContainExactlyInAnyOrder listOf("SBER")
    }

    @Test
    fun `unsubscribe removes only specified tickers`() {
        val hub = WsHub()
        val session = mockk<WebSocketSession>()
        hub.add(session)
        hub.subscribe(session, listOf("SBER", "GAZP", "LKOH"))

        hub.unsubscribe(session, listOf("GAZP"))

        val remaining = hub.subscriptions(session)
        remaining shouldContainExactlyInAnyOrder listOf("SBER", "LKOH")
        remaining shouldNotContain "GAZP"
    }

    @Test
    fun `subscribe before add is no-op`() {
        val hub = WsHub()
        val session = mockk<WebSocketSession>()

        // Не добавили session — subscribe не должен бросать.
        hub.subscribe(session, listOf("SBER"))
        hub.subscriptions(session) shouldBe emptySet()
    }

    @Test
    fun `remove cleans up subscriptions`() {
        val hub = WsHub()
        val session = mockk<WebSocketSession>()
        hub.add(session)
        hub.subscribe(session, listOf("SBER"))

        hub.remove(session)

        hub.subscriptions(session) shouldBe emptySet()
        hub.activeSessions() shouldBe 0
    }

    @Test
    fun `multiple sessions independent`() {
        val hub = WsHub()
        val s1 = mockk<WebSocketSession>()
        val s2 = mockk<WebSocketSession>()

        hub.add(s1)
        hub.add(s2)
        hub.subscribe(s1, listOf("SBER"))
        hub.subscribe(s2, listOf("GAZP"))

        hub.activeSessions() shouldBe 2
        hub.subscriptions(s1) shouldContainExactlyInAnyOrder listOf("SBER")
        hub.subscriptions(s2) shouldContainExactlyInAnyOrder listOf("GAZP")
    }
}
