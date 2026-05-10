package com.stockyard.core.telemetry

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.Tracer

/**
 * Wrapper над GlobalOpenTelemetry. Heavy lifting (auto-instrumentation Ktor,
 * HikariCP+JDBC для PG и CH, Lettuce) делает OTel Java Agent через
 * JAVA_TOOL_OPTIONS (см. Dockerfile + 09-observability §9.12).
 *
 * Этот объект — для кастомных бизнес-spans вокруг TX (`stockyard.tx.kind`,
 * `stockyard.user_id`, `stockyard.order_id`) в будущих TASK'ах (TASK-005/006/007).
 */
object Telemetry {
    val openTelemetry: OpenTelemetry get() = GlobalOpenTelemetry.get()
    val tracer: Tracer get() = openTelemetry.getTracer("com.stockyard.core", "0.2.0")
    val meter: Meter get() = openTelemetry.getMeter("com.stockyard.core")
}
