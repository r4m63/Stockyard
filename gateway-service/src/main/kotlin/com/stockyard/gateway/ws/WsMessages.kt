package com.stockyard.gateway.ws

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Inbound (Client → Gateway) frames. Manual dispatch on `action` discriminator —
 * forward-compatible (unknown actions → caller emits INVALID_FRAME, conn stays open).
 */
sealed class InboundFrame {
    data class Subscribe(val tickers: List<String>) : InboundFrame()
    data class Unsubscribe(val tickers: List<String>) : InboundFrame()
    data object Ping : InboundFrame()
}

/**
 * Outbound (Gateway → Client) frames. Kotlinx polymorphic codec with
 * `classDiscriminator = "type"` — frozen ADR-011 wire format (cents-integer).
 */
@Serializable
sealed class OutboundFrame {

    @Serializable
    @SerialName("quote")
    data class Quote(
        val ticker: String,
        val ts: String,
        val tsNs: Long,
        val bidCents: Long,
        val askCents: Long,
        val lastCents: Long,
        val volume: Long,
    ) : OutboundFrame()

    @Serializable
    @SerialName("subscribed")
    data class SubAck(val tickers: List<String>) : OutboundFrame()

    @Serializable
    @SerialName("unsubscribed")
    data class UnsubAck(val tickers: List<String>) : OutboundFrame()

    @Serializable
    @SerialName("pong")
    data object Pong : OutboundFrame()

    @Serializable
    @SerialName("error")
    data class Error(val code: String, val message: String) : OutboundFrame()
}

/** Returns the wire `type` value of an outbound frame — used for metric labels. */
fun OutboundFrame.typeLabel(): String = when (this) {
    is OutboundFrame.Quote -> "quote"
    is OutboundFrame.SubAck -> "subscribed"
    is OutboundFrame.UnsubAck -> "unsubscribed"
    OutboundFrame.Pong -> "pong"
    is OutboundFrame.Error -> "error"
}

/**
 * Single Json instance for outbound serialization. Polymorphic dispatch via
 * `classDiscriminator = "type"`; explicit `OutboundFrame.serializer()` is
 * required at the call site to keep dispatch on the declared sealed type.
 */
val outboundJson: Json = Json {
    classDiscriminator = "type"
    encodeDefaults = false
    ignoreUnknownKeys = true
}

private val inboundJson: Json = Json { ignoreUnknownKeys = true; isLenient = true }

fun encodeOutbound(frame: OutboundFrame): String =
    outboundJson.encodeToString(OutboundFrame.serializer(), frame)

/**
 * Decode a client text frame. Returns null on parse failure or unknown `action`
 * value — caller must emit `OutboundFrame.Error("INVALID_FRAME", ...)` without
 * closing the connection.
 */
fun decodeInbound(text: String): InboundFrame? = runCatching {
    val obj: JsonObject = inboundJson.parseToJsonElement(text).jsonObject
    val action = obj["action"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
    when (action) {
        "subscribe" -> InboundFrame.Subscribe(extractTickers(obj))
        "unsubscribe" -> InboundFrame.Unsubscribe(extractTickers(obj))
        "ping" -> InboundFrame.Ping
        else -> null
    }
}.getOrNull()

/**
 * Decode a Pub/Sub message into [OutboundFrame.Quote]. Used by [QuotesSubscriber]
 * — payload has no `type` field (ADR-011 wire shape), explicit serializer skips
 * polymorphic dispatch.
 */
fun decodeQuote(json: Json, payload: String): OutboundFrame.Quote? = runCatching {
    json.decodeFromString(OutboundFrame.Quote.serializer(), payload)
}.getOrNull()

private fun extractTickers(obj: JsonObject): List<String> =
    obj["tickers"]?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        ?.filter { it.isNotBlank() }
        .orEmpty()
