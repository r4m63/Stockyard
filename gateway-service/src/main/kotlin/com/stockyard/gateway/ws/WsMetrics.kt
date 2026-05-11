package com.stockyard.gateway.ws

import com.stockyard.gateway.telemetry.Telemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.LongCounter

/**
 * OTel counters/gauges for the WS fanout subsystem. Counters are bumped from hot
 * paths (Redis pub/sub callback, hub mutations, writer coroutine); the active-
 * connections gauge polls [WsHub.activeConnections] on every scrape.
 *
 * Wire names match TASK-010 Q6 stakeholder spec:
 *  - gauge `ws_active_connections`
 *  - counter `ws_shutdown_leaked_total{reason}` with reason ∈ `timeout`, `send_failed`
 */
class WsMetrics {
    private val meter = Telemetry.meter

    val subscriptions: LongCounter = meter
        .counterBuilder("ws_subscriptions_total")
        .setDescription("WS subscriptions added (after hard-cap filtering)")
        .build()

    val framesSent: LongCounter = meter
        .counterBuilder("ws_frames_sent_total")
        .setDescription("WS outbound frames written to the socket")
        .build()

    val framesDropped: LongCounter = meter
        .counterBuilder("ws_frames_dropped_backpressure_total")
        .setDescription("WS outbound frames dropped via DROP_OLDEST backpressure")
        .build()

    val pubsubMessages: LongCounter = meter
        .counterBuilder("redis_pubsub_messages_received_total")
        .setDescription("Redis Pub/Sub messages received on channel:quotes:*")
        .build()

    val pubsubParseErrors: LongCounter = meter
        .counterBuilder("redis_pubsub_parse_errors_total")
        .setDescription("Redis Pub/Sub payloads that failed JSON decode")
        .build()

    /**
     * WS connections that the shutdown drain failed to close cleanly. `reason`
     * is `timeout` (closeAll exceeded the deadline) or `send_failed` (per-conn
     * close frame threw). Without this counter, `withTimeoutOrNull` would
     * swallow shutdown leaks silently (Q6).
     */
    val shutdownLeaked: LongCounter = meter
        .counterBuilder("ws_shutdown_leaked_total")
        .setDescription("WS connections not cleanly closed during shutdown drain")
        .build()

    /** Wire the active-connections gauge to a snapshot provider (typically `hub::activeConnections`). */
    fun registerActiveConnections(provider: () -> Long) {
        meter.gaugeBuilder("ws_active_connections")
            .ofLongs()
            .setDescription("Active WS connections held by the hub")
            .buildWithCallback { measurement -> measurement.record(provider()) }
    }

    fun typeAttrs(type: String): Attributes = Attributes.of(TYPE_KEY, type)
    fun reasonAttrs(reason: String): Attributes = Attributes.of(REASON_KEY, reason)

    companion object {
        private val TYPE_KEY: AttributeKey<String> = AttributeKey.stringKey("type")
        private val REASON_KEY: AttributeKey<String> = AttributeKey.stringKey("reason")
    }
}
