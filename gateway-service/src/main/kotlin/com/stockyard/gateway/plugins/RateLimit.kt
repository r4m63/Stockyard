package com.stockyard.gateway.plugins

import com.stockyard.gateway.redis.RedisModule
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.plugins.origin
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory

/**
 * Rate-limit плагин (TASK-015 / ADR-012).
 *
 * Sliding-counter подход: ключ `ratelimit:ip:{ip}:{epochSec}`, операция
 * INCR + (на первой инкрементации) EXPIRE на windowSec+1 сек, чтобы покрыть
 * границу. Окно 1 сек = эквивалент RPS-ограничения.
 *
 * **Гранулярность**: per-IP. Per-user (через JWTPrincipal) требует, чтобы
 * плагин запускался ВНУТРИ `authenticate("auth-jwt") { ... }` блока — в MVP
 * IP-based достаточно: единственный публично-открытый эндпойнт — /v1/auth/...,
 * остальные за JWT-стеной. Per-user — Backlog.
 *
 * **Fail-open**: при недоступности Redis плагин логирует WARN и пропускает
 * запрос. Альтернатива (deny-on-error) даст ложноположительные блоки при
 * каждом транзиентном сбое Redis.
 *
 * См. docs/architecture/06-data.md §6.3.2.
 */
data class RateLimitConfig(
    var redis: RedisModule? = null,
    var perIpLimit: Int = 50,
    var windowSec: Long = 1,
    var skipPaths: List<String> = listOf("/health", "/metrics", "/v1/ws"),
)

val RateLimitPlugin = createApplicationPlugin(
    name = "RateLimit",
    createConfiguration = ::RateLimitConfig,
) {
    val log = LoggerFactory.getLogger("RateLimitPlugin")
    val redis = pluginConfig.redis ?: error("RateLimitConfig.redis must be set")
    val limit = pluginConfig.perIpLimit
    val window = pluginConfig.windowSec
    val skip = pluginConfig.skipPaths.toList()

    onCall { call ->
        val path = call.request.path()
        if (skip.any { path.startsWith(it) }) return@onCall

        val ip = call.request.origin.remoteHost
        val now = System.currentTimeMillis() / 1000
        val key = "ratelimit:ip:$ip:$now"

        val count = try {
            redis.withCommandConnection { conn ->
                val sync = conn.sync()
                val n = sync.incr(key)
                if (n == 1L) sync.expire(key, window + 1)
                n
            }
        } catch (e: Exception) {
            log.warn("rate-limit redis failure on ip {}: {}", ip, e.message)
            return@onCall
        }

        call.response.header("RateLimit-Limit", limit.toString())
        call.response.header("RateLimit-Remaining", (limit - count).coerceAtLeast(0L).toString())
        call.response.header("RateLimit-Reset", window.toString())

        if (count > limit) {
            call.response.header("Retry-After", window.toString())
            call.respond(
                HttpStatusCode.TooManyRequests,
                buildJsonObject {
                    put(
                        "error",
                        buildJsonObject {
                            put("code", JsonPrimitive("RATE_LIMITED"))
                            put("message", JsonPrimitive("rate limit exceeded"))
                            put(
                                "details",
                                buildJsonObject {
                                    put("scope", JsonPrimitive("ip"))
                                    put("limit", JsonPrimitive(limit))
                                    put("windowSec", JsonPrimitive(window))
                                },
                            )
                        },
                    )
                },
            )
        }
    }
}
