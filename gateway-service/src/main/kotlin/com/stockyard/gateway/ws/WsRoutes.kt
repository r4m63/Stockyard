package com.stockyard.gateway.ws

import com.auth0.jwt.exceptions.JWTVerificationException
import com.stockyard.gateway.auth.JwtVerifiers
import com.stockyard.gateway.auth.SessionStore
import com.stockyard.gateway.redis.RedisModule
import io.azam.ulidj.ULID
import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * WebSocket endpoint `/v1/ws/quotes`. Specializes §5.3.3 generic `/v1/ws`;
 * TASK-011 will sync the doc.
 *
 * Auth: JWT in query (`?token=<jwt>`), verified manually inside the
 * `webSocket {}` block — see ADR-014 in TASK-010 ledger for the rationale
 * (custom `jwt {}` authenticator is messy with query tokens). JWT expiry
 * mid-session is **not** enforced (§5.3.3).
 *
 * Lifecycle per connection:
 *   1. handshake → manual JWT verify → register in [WsHub]
 *   2. spawn three coroutines: reader, writer, heartbeat (structured
 *      concurrency via `coroutineScope`)
 *   3. on exit (cleanly or via cancellation), `finally` unregisters
 *
 * Single-writer rule: the writer coroutine is the **only** caller of
 * `outgoing.send`. Reader, heartbeat, and snapshot routines push frames into
 * `state.outbound` (the per-connection `Channel`).
 */
fun Route.wsRoutes(
    hub: WsHub,
    jwt: JwtVerifiers,
    sessions: SessionStore,
    metrics: WsMetrics,
    redis: RedisModule,
) {
    val log = LoggerFactory.getLogger("com.stockyard.gateway.ws.WsRoutes")

    webSocket("/v1/ws/quotes") {
        val token = call.request.queryParameters[TOKEN_QUERY]
        if (token.isNullOrBlank()) {
            close(CloseReason(CODE_AUTH_FAILED, "missing token"))
            return@webSocket
        }
        val decoded = try {
            jwt.accessVerifier.verify(token)
        } catch (e: JWTVerificationException) {
            close(CloseReason(CODE_AUTH_FAILED, "invalid token"))
            return@webSocket
        }
        val userId = decoded.subject?.takeIf { it.isNotBlank() }
        val jti = decoded.id?.takeIf { it.isNotBlank() }
        if (userId == null || jti == null) {
            close(CloseReason(CODE_AUTH_FAILED, "bad claims"))
            return@webSocket
        }
        if (!sessions.accessSessionExists(jti)) {
            close(CloseReason(CODE_AUTH_FAILED, "session revoked"))
            return@webSocket
        }

        val connId = ULID.random()
        val state = WsHub.ConnState(connId, userId, this)
        if (!hub.register(state)) {
            close(CloseReason(CODE_USER_CONN_LIMIT, "too many connections"))
            return@webSocket
        }
        log.atDebug()
            .addKeyValue("user.id", userId)
            .addKeyValue("conn.id", connId)
            .log("WS connection established")

        try {
            runSession(state, hub, redis, metrics)
        } finally {
            hub.unregister(connId)
            log.atDebug()
                .addKeyValue("user.id", userId)
                .addKeyValue("conn.id", connId)
                .log("WS connection closed")
        }
    }
}

private suspend fun DefaultWebSocketServerSession.runSession(
    state: WsHub.ConnState,
    hub: WsHub,
    redis: RedisModule,
    metrics: WsMetrics,
) = coroutineScope {
    val writerJob = launch {
        for (frame in state.outbound) {
            val text = encodeOutbound(frame)
            try {
                send(Frame.Text(text))
                metrics.framesSent.add(1, metrics.typeAttrs(frame.typeLabel()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return@launch
            }
        }
    }
    val heartbeatJob = launch {
        while (isActive) {
            delay(HEARTBEAT_PERIOD_MS)
            if (state.outbound.trySend(OutboundFrame.Pong).isFailure) {
                metrics.framesDropped.add(1, metrics.typeAttrs(OutboundFrame.Pong.typeLabel()))
            }
        }
    }
    try {
        for (frame in incoming) {
            if (frame !is Frame.Text) continue
            handleInbound(state, frame.readText(), hub, redis, metrics)
        }
    } finally {
        heartbeatJob.cancel()
        writerJob.cancel()
    }
}

private fun handleInbound(
    state: WsHub.ConnState,
    text: String,
    hub: WsHub,
    redis: RedisModule,
    metrics: WsMetrics,
) {
    val inbound = decodeInbound(text)
    if (inbound == null) {
        state.outbound.trySend(OutboundFrame.Error("INVALID_FRAME", "unknown action or invalid JSON"))
        return
    }
    when (inbound) {
        is InboundFrame.Subscribe -> handleSubscribe(state, inbound.tickers, hub, redis, metrics)
        is InboundFrame.Unsubscribe -> {
            val removed = hub.removeSubscriptions(state.connId, inbound.tickers)
            state.outbound.trySend(OutboundFrame.UnsubAck(removed))
        }
        InboundFrame.Ping -> state.outbound.trySend(OutboundFrame.Pong)
    }
}

private fun handleSubscribe(
    state: WsHub.ConnState,
    tickers: List<String>,
    hub: WsHub,
    redis: RedisModule,
    metrics: WsMetrics,
) {
    val result = hub.addSubscriptions(state.connId, tickers)
    val accepted = when (result) {
        is WsHub.SubscribeResult.Ok -> result.added
        is WsHub.SubscribeResult.CapExceeded -> {
            state.outbound.trySend(
                OutboundFrame.Error("SUBSCRIPTION_LIMIT", "max ${WsHub.MAX_SUBS_PER_CONN} tickers/connection"),
            )
            result.accepted
        }
    }
    accepted.forEach { ticker -> sendSnapshot(state, ticker, redis, metrics) }
    state.outbound.trySend(OutboundFrame.SubAck(accepted))
}

private fun sendSnapshot(
    state: WsHub.ConnState,
    ticker: String,
    redis: RedisModule,
    metrics: WsMetrics,
) {
    val map: Map<String, String> = runCatching {
        redis.withCommandConnection { it.sync().hgetall("quotes:$ticker") }
    }.getOrElse { return }
    if (map.isEmpty()) return
    val frame = runCatching {
        OutboundFrame.Quote(
            ticker = ticker,
            ts = map.getValue("ts"),
            tsNs = map.getValue("ts_ns").toLong(),
            bidCents = map.getValue("bid").toLong(),
            askCents = map.getValue("ask").toLong(),
            lastCents = map.getValue("last").toLong(),
            volume = map["volume"]?.toLong() ?: 0L,
        )
    }.getOrElse { return }
    if (state.outbound.trySend(frame).isFailure) {
        metrics.framesDropped.add(1, metrics.typeAttrs(frame.typeLabel()))
    }
}

private const val TOKEN_QUERY = "token"
private const val CODE_AUTH_FAILED: Short = 4001
private const val CODE_USER_CONN_LIMIT: Short = 4002
private const val HEARTBEAT_PERIOD_MS: Long = 30_000
