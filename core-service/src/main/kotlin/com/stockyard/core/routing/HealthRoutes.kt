package com.stockyard.core.routing

import com.stockyard.core.persistence.DataSources
import com.stockyard.core.redis.RedisModule
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.micrometer.prometheus.PrometheusMeterRegistry
import kotlinx.serialization.Serializable

@Serializable
private data class HealthResponse(
    val status: String,
    val checks: Map<String, String> = emptyMap(),
)

fun Route.healthRoutes(
    ds: DataSources,
    redis: RedisModule,
    prometheusRegistry: PrometheusMeterRegistry,
) {
    /** Liveness: процесс жив, downstream не трогается. */
    get("/health/live") {
        call.respond(HealthResponse(status = "UP"))
    }

    /**
     * Readiness:
     *  - PG и Redis — **блокирующие** критерии. Без них бизнес-логика
     *    (TASK-005/006) работать не может.
     *  - ClickHouse — **info-only**. CH-недоступность означает только что
     *    история свечей (TASK-008) деградирует; ордерные эндпоинты живут.
     *    См. 12-storage-operations §12.6 «Storage failure modes».
     */
    get("/health/ready") {
        val checks = mutableMapOf<String, String>()
        checks["postgres"] = if (ds.pgPing()) "UP" else "DOWN"
        checks["redis"] = if (redis.ping()) "UP" else "DOWN"
        checks["clickhouse"] = if (ds.clickhousePing()) "UP" else "DOWN"

        val criticalUp = checks["postgres"] == "UP" && checks["redis"] == "UP"
        val httpStatus = if (criticalUp) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
        call.respond(httpStatus, HealthResponse(status = if (criticalUp) "UP" else "DOWN", checks = checks))
    }

    /** Prometheus scrape endpoint. */
    get("/metrics") {
        call.respond(prometheusRegistry.scrape())
    }
}
