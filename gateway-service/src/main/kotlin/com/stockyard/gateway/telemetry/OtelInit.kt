package com.stockyard.gateway.telemetry

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.Tracer

/**
 * Wrapper над GlobalOpenTelemetry. Heavy lifting (auto-instrumentation Ktor,
 * Lettuce, Ktor-client) делает OTel Java Agent через JAVA_TOOL_OPTIONS
 * (см. Dockerfile + docs/architecture/09-observability.md §9.12.1).
 *
 * Этот объект — для кастомных бизнес-spans (`stockyard.user_id`,
 * `stockyard.order_id`) в эндпоинтах будущих TASK'ов.
 */
object Telemetry {
    val openTelemetry: OpenTelemetry get() = GlobalOpenTelemetry.get()
    val tracer: Tracer get() = openTelemetry.getTracer("com.stockyard.gateway", "0.1.0")
    val meter: Meter get() = openTelemetry.getMeter("com.stockyard.gateway")
}
