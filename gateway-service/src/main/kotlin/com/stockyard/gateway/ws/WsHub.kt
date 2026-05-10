package com.stockyard.gateway.ws

import io.ktor.websocket.WebSocketSession
import java.util.concurrent.ConcurrentHashMap

/**
 * Реестр WS-соединений и их подписок на тикеры.
 *
 * В TASK-003 — skeleton. Реальный fanout из Redis Pub/Sub реализуется в TASK-008,
 * там же hub получит метод `broadcast(ticker, payload)` который пойдёт по
 * `sessions.filter { ticker in it.value }` и пошлёт frame.
 */
class WsHub {
    private val sessions: MutableMap<WebSocketSession, MutableSet<String>> = ConcurrentHashMap()

    fun add(session: WebSocketSession) {
        sessions[session] = ConcurrentHashMap.newKeySet()
    }

    fun remove(session: WebSocketSession) {
        sessions.remove(session)
    }

    fun subscribe(session: WebSocketSession, tickers: Collection<String>) {
        sessions[session]?.addAll(tickers)
    }

    fun unsubscribe(session: WebSocketSession, tickers: Collection<String>) {
        sessions[session]?.removeAll(tickers.toSet())
    }

    fun subscriptions(session: WebSocketSession): Set<String> = sessions[session].orEmpty()

    fun activeSessions(): Int = sessions.size
}
