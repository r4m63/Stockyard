package com.stockyard.gateway.ws

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

/**
 * In-process registry of WS connections, subscriptions, and per-user counts.
 *
 * Indexes:
 *  - `byConn`   — connId → ConnState (hot lookup from the WS handler)
 *  - `byTicker` — ticker → connIds (hot fanout from QuotesSubscriber)
 *  - `byUser`   — userId → connIds (enforces per-user connection cap)
 *
 * Compound mutations (subscribe = update `state.tickers` + `byTicker`) are
 * guarded by a per-conn intrinsic lock; the indexes themselves are
 * ConcurrentHashMap-backed for lock-free reads on the fanout path.
 *
 * See TASK-010 ledger §Architect Design (round 2) — ADR-013 (single pattern
 * subscribe + in-process fanout) and ADR-014 (JWT-in-query handshake) live
 * there until docs sync in TASK-011.
 */
class WsHub(
    private val metrics: WsMetrics,
    private val maxSubsPerConn: Int = MAX_SUBS_PER_CONN,
    private val maxConnsPerUser: Int = MAX_CONNS_PER_USER,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val byConn = ConcurrentHashMap<String, ConnState>()
    private val byTicker = ConcurrentHashMap<String, MutableSet<String>>()
    private val byUser = ConcurrentHashMap<String, MutableSet<String>>()

    init {
        metrics.registerActiveConnections { byConn.size.toLong() }
    }

    /**
     * Per-connection state. `outbound` is the single queue feeding the writer
     * coroutine — DROP_OLDEST keeps producers non-blocking under slow-reader
     * scenarios (ADR-001 at-most-once).
     */
    data class ConnState(
        val connId: String,
        val userId: String,
        val session: DefaultWebSocketServerSession,
        val outbound: Channel<OutboundFrame> = Channel(OUTBOUND_BUFFER, BufferOverflow.DROP_OLDEST),
        val tickers: MutableSet<String> = ConcurrentHashMap.newKeySet(),
    )

    sealed class SubscribeResult {
        /** All requested new tickers accepted. `added` is the de-duped list of newly subscribed tickers. */
        data class Ok(val added: List<String>) : SubscribeResult()
        /** Hard cap reached; `accepted` is whatever fit before the cap (possibly empty). */
        data class CapExceeded(val accepted: List<String>) : SubscribeResult()
    }

    /**
     * Register a new connection. Returns false if the per-user cap is reached —
     * caller emits WS close 4002.
     */
    fun register(state: ConnState): Boolean {
        val userConns = byUser.computeIfAbsent(state.userId) { ConcurrentHashMap.newKeySet() }
        synchronized(userConns) {
            if (userConns.size >= maxConnsPerUser) return false
            userConns.add(state.connId)
        }
        byConn[state.connId] = state
        return true
    }

    /** Remove a connection and clean every index. Idempotent. */
    fun unregister(connId: String) {
        val state = byConn.remove(connId) ?: return
        synchronized(state) {
            state.tickers.forEach { ticker -> byTicker[ticker]?.remove(connId) }
            state.tickers.clear()
        }
        byUser[state.userId]?.remove(connId)
        state.outbound.close()
    }

    /**
     * Add subscriptions for a connection. Hard cap is enforced **across the
     * existing set** — re-subscribing to a ticker the conn already holds is a
     * no-op and does not consume capacity.
     */
    fun addSubscriptions(connId: String, tickers: Collection<String>): SubscribeResult {
        val state = byConn[connId] ?: return SubscribeResult.Ok(emptyList())
        synchronized(state) {
            val newOnes = tickers.distinct().filter { it !in state.tickers }
            val capacity = (maxSubsPerConn - state.tickers.size).coerceAtLeast(0)
            val accepted = newOnes.take(capacity)
            accepted.forEach { ticker ->
                state.tickers.add(ticker)
                byTicker.computeIfAbsent(ticker) { ConcurrentHashMap.newKeySet() }.add(connId)
            }
            if (accepted.isNotEmpty()) {
                metrics.subscriptions.add(accepted.size.toLong())
            }
            return if (accepted.size < newOnes.size) SubscribeResult.CapExceeded(accepted)
            else SubscribeResult.Ok(accepted)
        }
    }

    /** Remove subscriptions; returns the list of tickers actually removed (for the ack frame). */
    fun removeSubscriptions(connId: String, tickers: Collection<String>): List<String> {
        val state = byConn[connId] ?: return emptyList()
        val removed = mutableListOf<String>()
        synchronized(state) {
            tickers.distinct().forEach { ticker ->
                if (state.tickers.remove(ticker)) {
                    byTicker[ticker]?.remove(connId)
                    removed.add(ticker)
                }
            }
        }
        return removed
    }

    /**
     * Hot path called by [QuotesSubscriber] on every Redis Pub/Sub message.
     * Snapshot iteration of the per-ticker connId set — CHM iteration is
     * weakly-consistent which is fine: a conn unregistered mid-fanout just
     * yields a closed [Channel.trySend] (harmless).
     */
    fun connectionsFor(ticker: String): Collection<ConnState> {
        val connIds = byTicker[ticker] ?: return emptyList()
        return connIds.mapNotNull { byConn[it] }
    }

    fun userConnectionCount(userId: String): Int = byUser[userId]?.size ?: 0

    fun activeConnections(): Long = byConn.size.toLong()

    /**
     * Graceful broadcast close. Sends a [CloseReason] to every connection in
     * parallel with a 2-second total timeout, then clears every index. Run from
     * `ApplicationStopping` via `runBlocking { closeAll(1001) }`.
     *
     * Leak accounting (Q6): emits `ws_shutdown_leaked_total{reason}` on
     * timeout (whole batch) or per-conn send failure. `withTimeoutOrNull`
     * alone would silently swallow these.
     */
    suspend fun closeAll(code: Short, reason: String = "server shutdown") {
        val snapshot = byConn.values.toList()
        if (snapshot.isEmpty()) return
        log.atInfo()
            .addKeyValue("connections", snapshot.size)
            .addKeyValue("code", code.toInt())
            .log("WsHub.closeAll")
        val result = withTimeoutOrNull(CLOSE_ALL_TIMEOUT_MS) {
            coroutineScope {
                snapshot.map { state ->
                    async {
                        val outcome = runCatching { state.session.close(CloseReason(code, reason)) }
                        state.outbound.close()
                        outcome.isFailure
                    }
                }.awaitAll()
            }
        }
        if (result == null) {
            metrics.shutdownLeaked.add(snapshot.size.toLong(), metrics.reasonAttrs("timeout"))
            log.atWarn()
                .addKeyValue("connections", snapshot.size)
                .log("WsHub.closeAll exceeded timeout — connections leaked")
        } else {
            val sendFailed = result.count { it }
            if (sendFailed > 0) {
                metrics.shutdownLeaked.add(sendFailed.toLong(), metrics.reasonAttrs("send_failed"))
                log.atWarn()
                    .addKeyValue("send_failed", sendFailed)
                    .log("WsHub.closeAll: send_failed connections")
            }
        }
        byConn.clear()
        byTicker.clear()
        byUser.clear()
    }

    companion object {
        const val MAX_SUBS_PER_CONN = 100
        const val MAX_CONNS_PER_USER = 5
        const val OUTBOUND_BUFFER = 256
        private const val CLOSE_ALL_TIMEOUT_MS = 2_000L
    }
}
