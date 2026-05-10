package com.stockyard.gateway.config

import io.ktor.server.application.Application

data class AppConfig(
    val jwt: JwtConfig,
    val redis: RedisConfig,
    val coreService: CoreServiceConfig,
    val otel: OtelConfig,
)

data class JwtConfig(
    val secret: String,
    val issuer: String,
    val audience: String,
    val accessTtlSeconds: Long,
    val refreshTtlSeconds: Long,
)

data class RedisConfig(
    val url: String,
    val password: String,
)

data class CoreServiceConfig(
    val baseUrl: String,
    val connectTimeoutMs: Long,
    val requestTimeoutMs: Long,
)

data class OtelConfig(
    val serviceName: String,
    val otlpEndpoint: String,
)

fun Application.loadAppConfig(): AppConfig {
    val cfg = environment.config.config("stockyard")
    val jwt = cfg.config("jwt")
    val redis = cfg.config("redis")
    val core = cfg.config("coreService")
    val otel = cfg.config("otel")
    return AppConfig(
        jwt = JwtConfig(
            secret = jwt.property("secret").getString(),
            issuer = jwt.property("issuer").getString(),
            audience = jwt.property("audience").getString(),
            accessTtlSeconds = jwt.property("accessTtlSeconds").getString().toLong(),
            refreshTtlSeconds = jwt.property("refreshTtlSeconds").getString().toLong(),
        ),
        redis = RedisConfig(
            url = redis.property("url").getString(),
            password = redis.property("password").getString(),
        ),
        coreService = CoreServiceConfig(
            baseUrl = core.property("baseUrl").getString(),
            connectTimeoutMs = core.property("connectTimeoutMs").getString().toLong(),
            requestTimeoutMs = core.property("requestTimeoutMs").getString().toLong(),
        ),
        otel = OtelConfig(
            serviceName = otel.property("serviceName").getString(),
            otlpEndpoint = otel.property("otlpEndpoint").getString(),
        ),
    )
}
