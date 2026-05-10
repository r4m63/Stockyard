package com.stockyard.gateway.ws

import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * WebSocket endpoint `/v1/ws`. В TASK-003 — echo-skeleton: принимает
 * `subscribe`/`unsubscribe`/`ping` от клиента, отвечает `subscribed`/
 * `unsubscribed`/`pong`. Реальный fanout котировок (Redis Pub/Sub →
 * клиент) — TASK-008.
 *
 * Протокол см. docs/architecture/05-communication.md §5.3.3.
 */
private val json = Json { ignoreUnknownKeys = true }

@Serializable
private data class IncomingMessage(
    val action: String,
    val tickers: List<String> = emptyList(),
)

fun Route.wsRoutes(hub: WsHub) {
    // TODO(TASK-008): validate JWT before accepting connection.
    // Контракт §5.3.3: wss://stockyard.example/v1/ws?token=<JWT> либо
    // через Authorization-subprotocol. До этого endpoint открыт всем — это
    // намеренная дырка scaffold-этапа, не пускайте на demo.
    webSocket("/v1/ws") {
        hub.add(this)
        try {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                handleClientMessage(frame.readText(), hub)
            }
        } finally {
            hub.remove(this)
        }
    }
}

private suspend fun DefaultWebSocketServerSession.handleClientMessage(text: String, hub: WsHub) {
    val msg = runCatching { json.decodeFromString<IncomingMessage>(text) }.getOrElse {
        sendJson(buildJsonObject {
            put("type", "error")
            put("code", "INVALID_FRAME")
            put("message", "JSON parse failure")
        })
        return
    }
    when (msg.action) {
        "subscribe" -> {
            hub.subscribe(this, msg.tickers)
            sendJson(buildJsonObject {
                put("type", "subscribed")
                put("tickers", buildJsonArray { msg.tickers.forEach { add(it) } })
            })
        }
        "unsubscribe" -> {
            hub.unsubscribe(this, msg.tickers)
            sendJson(buildJsonObject {
                put("type", "unsubscribed")
                put("tickers", buildJsonArray { msg.tickers.forEach { add(it) } })
            })
        }
        "ping" -> sendJson(buildJsonObject { put("type", "pong") })
        else -> sendJson(buildJsonObject {
            put("type", "error")
            put("code", "UNKNOWN_ACTION")
            put("message", "Unknown action")
        })
    }
}

private suspend fun DefaultWebSocketServerSession.sendJson(payload: JsonObject) {
    send(Frame.Text(payload.toString()))
}
