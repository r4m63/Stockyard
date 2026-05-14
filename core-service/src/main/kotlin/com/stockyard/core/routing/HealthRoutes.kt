package com.stockyard.core.routing

import com.stockyard.core.persistence.DataSources
import com.stockyard.core.redis.RedisModule
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
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

    /**
     * Startup: PG ping required (Flyway уже отработала, иначе HTTP-сокет не открыт).
     * Redis тоже обязателен — без него фикстура/Quotes Service не работают, и `QuotesPort`
     * на /v1/quotes падает. ClickHouse — info-only.
     */
    get("/health/startup") {
        val checks = mutableMapOf<String, String>()
        checks["postgres"] = if (ds.pgPing()) "UP" else "DOWN"
        checks["redis"] = if (redis.ping()) "UP" else "DOWN"
        val allUp = checks.values.all { it == "UP" }
        call.respond(
            if (allUp) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
            HealthResponse(status = if (allUp) "UP" else "STARTING", checks = checks),
        )
    }

    /** Prometheus scrape endpoint. */
    get("/metrics") {
        call.respond(prometheusRegistry.scrape())
    }
}
