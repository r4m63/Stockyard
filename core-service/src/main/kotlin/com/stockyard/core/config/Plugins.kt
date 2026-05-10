package com.stockyard.core.config

import com.stockyard.core.error.installErrorMapping
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.path
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

/**
 * Возвращает Prometheus registry — он шарится с роутом /metrics.
 */
fun Application.installPlugins(): PrometheusMeterRegistry {
    // StatusPages должен быть установлен ПЕРВЫМ (см. /reviewer TASK-003 finding M2):
    // перехватит ошибки, выброшенные другими плагинами и роутами, до респонса.
    installErrorMapping()

    install(ContentNegotiation) {
        json(Json {
            prettyPrint = false
            ignoreUnknownKeys = true
            encodeDefaults = false
        })
    }

    install(CallLogging) {
        level = Level.INFO
        filter { call ->
            val p = call.request.path()
            !p.startsWith("/health") && p != "/metrics"
        }
    }

    val prometheusRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    install(MicrometerMetrics) {
        registry = prometheusRegistry
        meterBinders = listOf(
            JvmMemoryMetrics(),
            JvmGcMetrics(),
            JvmThreadMetrics(),
            ProcessorMetrics(),
        )
    }
    return prometheusRegistry
}
