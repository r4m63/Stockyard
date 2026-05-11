package com.stockyard.gateway.ws

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class WsMessagesTest {

    private val json = Json { ignoreUnknownKeys = true }

    private infix fun String.shouldEqualJson(expected: String) {
        Json.parseToJsonElement(this) shouldBe Json.parseToJsonElement(expected)
    }

    // ----- Inbound (manual dispatch on `action`) ----------------------------

    @Test
    fun `decodeInbound subscribe with tickers`() {
        val frame = decodeInbound("""{"action":"subscribe","tickers":["SBER","GAZP"]}""")
        frame.shouldBeInstanceOf<InboundFrame.Subscribe>()
        frame.tickers shouldContainExactly listOf("SBER", "GAZP")
    }

    @Test
    fun `decodeInbound subscribe with empty tickers`() {
        val frame = decodeInbound("""{"action":"subscribe","tickers":[]}""")
        frame.shouldBeInstanceOf<InboundFrame.Subscribe>()
        frame.tickers.shouldHaveSize(0)
    }

    @Test
    fun `decodeInbound subscribe missing tickers field defaults to empty`() {
        val frame = decodeInbound("""{"action":"subscribe"}""")
        frame.shouldBeInstanceOf<InboundFrame.Subscribe>()
        frame.tickers.shouldHaveSize(0)
    }

    @Test
    fun `decodeInbound unsubscribe`() {
        val frame = decodeInbound("""{"action":"unsubscribe","tickers":["SBER"]}""")
        frame.shouldBeInstanceOf<InboundFrame.Unsubscribe>()
        frame.tickers shouldContainExactly listOf("SBER")
    }

    @Test
    fun `decodeInbound ping`() {
        decodeInbound("""{"action":"ping"}""") shouldBe InboundFrame.Ping
    }

    @Test
    fun `decodeInbound unknown action returns null`() {
        decodeInbound("""{"action":"hack","tickers":["SBER"]}""").shouldBeNull()
    }

    @Test
    fun `decodeInbound malformed JSON returns null`() {
        decodeInbound("this is not json").shouldBeNull()
    }

    @Test
    fun `decodeInbound missing action returns null`() {
        decodeInbound("""{"tickers":["SBER"]}""").shouldBeNull()
    }

    @Test
    fun `decodeInbound ignores unknown extra fields`() {
        val frame = decodeInbound(
            """{"action":"subscribe","tickers":["SBER"],"future_field":42,"nested":{"x":1}}""",
        )
        frame.shouldBeInstanceOf<InboundFrame.Subscribe>()
        frame.tickers shouldContainExactly listOf("SBER")
    }

    @Test
    fun `decodeInbound filters blank ticker strings`() {
        val frame = decodeInbound("""{"action":"subscribe","tickers":["SBER","","  ","GAZP"]}""")
        frame.shouldBeInstanceOf<InboundFrame.Subscribe>()
        frame.tickers shouldContainExactly listOf("SBER", "GAZP")
    }

    // ----- Outbound (kotlinx polymorphic, classDiscriminator="type") --------

    @Test
    fun `encodeOutbound quote — exact ADR-011 wire shape`() {
        val frame = OutboundFrame.Quote(
            ticker = "SBER",
            ts = "2026-05-09T12:34:56.789Z",
            tsNs = 1_746_789_296_789_012_345L,
            bidCents = 28550,
            askCents = 28570,
            lastCents = 28560,
            volume = 12345,
        )
        encodeOutbound(frame) shouldEqualJson """
            {
              "type":"quote",
              "ticker":"SBER",
              "ts":"2026-05-09T12:34:56.789Z",
              "tsNs":1746789296789012345,
              "bidCents":28550,
              "askCents":28570,
              "lastCents":28560,
              "volume":12345
            }
        """.trimIndent()
    }

    @Test
    fun `encodeOutbound quote — no Decimal or floating-point on the wire`() {
        val frame = OutboundFrame.Quote("SBER", "2026-01-01T00:00:00Z", 0L, 1L, 2L, 3L, 4L)
        val text = encodeOutbound(frame)
        text.shouldNotContain(".")
        text.shouldNotContain("e+")
        text.shouldNotContain("E+")
    }

    @Test
    fun `encodeOutbound subscribed ack`() {
        val frame = OutboundFrame.SubAck(listOf("SBER", "GAZP"))
        encodeOutbound(frame) shouldEqualJson """{"type":"subscribed","tickers":["SBER","GAZP"]}"""
    }

    @Test
    fun `encodeOutbound unsubscribed ack`() {
        val frame = OutboundFrame.UnsubAck(listOf("SBER"))
        encodeOutbound(frame) shouldEqualJson """{"type":"unsubscribed","tickers":["SBER"]}"""
    }

    @Test
    fun `encodeOutbound pong — object with type only`() {
        encodeOutbound(OutboundFrame.Pong) shouldEqualJson """{"type":"pong"}"""
    }

    @Test
    fun `encodeOutbound error`() {
        val frame = OutboundFrame.Error("SUBSCRIPTION_LIMIT", "max 100 tickers/connection")
        encodeOutbound(frame) shouldEqualJson """
            {"type":"error","code":"SUBSCRIPTION_LIMIT","message":"max 100 tickers/connection"}
        """.trimIndent()
    }

    // ----- decodeQuote (Pub/Sub payload — no `type` discriminator) ----------

    @Test
    fun `decodeQuote — frozen C2 wire from Quotes Service`() {
        val payload = """
            {"ticker":"SBER","ts":"2026-05-09T12:34:56.789Z","tsNs":1746789296789012345,
             "bidCents":28550,"askCents":28570,"lastCents":28560,"volume":12345}
        """.trimIndent()
        val quote = decodeQuote(json, payload)
        checkNotNull(quote)
        quote.ticker shouldBe "SBER"
        quote.tsNs shouldBe 1_746_789_296_789_012_345L
        quote.bidCents shouldBe 28550L
        quote.askCents shouldBe 28570L
        quote.lastCents shouldBe 28560L
        quote.volume shouldBe 12345L
    }

    @Test
    fun `decodeQuote — extra fields ignored`() {
        val payload = """
            {"ticker":"SBER","ts":"x","tsNs":0,"bidCents":1,"askCents":2,
             "lastCents":3,"volume":4,"futureField":"ignore-me"}
        """.trimIndent()
        decodeQuote(json, payload).shouldBeInstanceOf<OutboundFrame.Quote>()
    }

    @Test
    fun `decodeQuote — malformed JSON returns null`() {
        decodeQuote(json, "not-json").shouldBeNull()
    }

    @Test
    fun `decodeQuote — missing required field returns null`() {
        // bidCents missing
        decodeQuote(
            json,
            """{"ticker":"SBER","ts":"x","tsNs":0,"askCents":2,"lastCents":3,"volume":4}""",
        ).shouldBeNull()
    }

    // ----- Type label (metric dimension) -----------------------------------

    @Test
    fun `typeLabel maps every subtype`() {
        OutboundFrame.Quote("a", "b", 0, 0, 0, 0, 0).typeLabel() shouldBe "quote"
        OutboundFrame.SubAck(emptyList()).typeLabel() shouldBe "subscribed"
        OutboundFrame.UnsubAck(emptyList()).typeLabel() shouldBe "unsubscribed"
        OutboundFrame.Pong.typeLabel() shouldBe "pong"
        OutboundFrame.Error("X", "y").typeLabel() shouldBe "error"
    }

    @Test
    fun `encodeOutbound never emits encodeDefaults — null fields absent`() {
        // OutboundFrame has no nullable defaults, but ensure no stray `null`
        // value or `defaults` from kotlinx defaults appears.
        val text = encodeOutbound(OutboundFrame.Pong)
        text.shouldNotContain("null")
        text.shouldContain("\"type\":\"pong\"")
    }
}
