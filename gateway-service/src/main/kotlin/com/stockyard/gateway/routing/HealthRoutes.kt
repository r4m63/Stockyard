package com.stockyard.gateway.routing

import io.ktor.server.application.call

import com.stockyard.gateway.client.CoreServiceClient
import com.stockyard.gateway.redis.RedisModule
import com.stockyard.gateway.ws.QuotesSubscriber
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

@Serializable
private data class HealthResponse(
    val status: String,
    val checks: Map<String, String> = emptyMap(),
)

fun Route.healthRoutes(
    redis: RedisModule,
    core: CoreServiceClient,
    quotesSubscriber: QuotesSubscriber? = null,
) {
    /** Liveness: процесс жив, не делает обращений к downstream. */
    get("/health/live") {
        call.respond(HealthResponse(status = "UP"))
    }

    /**
     * Readiness: проверяем критичные downstream'ы.
     *
     * - Redis: блокирующий критерий — без Redis новые WS-клиенты не получают тиков,
     *   а размещение ордеров (TASK-006) требует HGET цены.
     * - Core Service: отображается в `checks`, но **не делает gateway unhealthy**,
     *   потому что в TASK-003 core-service может ещё не существовать. Состояние
     *   ужесточится после TASK-005, когда auth flow реально пойдёт через core.
     */
    get("/health/ready") {
        val checks = mutableMapOf<String, String>()
        checks["redis"] = if (redis.ping()) "UP" else "DOWN"
        checks["core-service"] = if (core.healthReady()) "UP" else "DOWN"

        val redisUp = checks["redis"] == "UP"
        val httpStatus = if (redisUp) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
        call.respond(httpStatus, HealthResponse(status = if (redisUp) "UP" else "DOWN", checks = checks))
    }

    /**
     * Startup: единственный probe для k8s `startupProbe`. Возвращает 200 ТОЛЬКО когда:
     *  - Redis отвечает на PING,
     *  - `QuotesSubscriber.psubscribe(channel:quotes:*)` уже выполнен (иначе WS-снапшоты пусты).
     * До этого момента — 503. Используется для grace-периода cold-start (TASK-015).
     */
    get("/health/startup") {
        val checks = mutableMapOf<String, String>()
        checks["redis"] = if (redis.ping()) "UP" else "DOWN"
        checks["quotes-subscriber"] = if (quotesSubscriber?.isStarted() == true) "UP" else "DOWN"

        val allUp = checks.values.all { it == "UP" }
        call.respond(
            if (allUp) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
            HealthResponse(status = if (allUp) "UP" else "STARTING", checks = checks),
        )
    }
}
