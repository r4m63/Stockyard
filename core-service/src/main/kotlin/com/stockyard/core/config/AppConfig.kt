package com.stockyard.core.config

import io.ktor.server.application.Application

data class AppConfig(
    val postgres: PostgresConfig,
    val redis: RedisConfig,
    val clickhouse: ClickHouseConfig,
    val argon2: Argon2Config,
    val otel: OtelConfig,
)

data class PostgresConfig(
    val host: String,
    val port: Int,
    val db: String,
    val user: String,
    val password: String,
) {
    val jdbcUrl: String get() = "jdbc:postgresql://$host:$port/$db"
}

data class RedisConfig(
    val url: String,
    val password: String,
)

data class ClickHouseConfig(
    val host: String,
    val port: Int,
    val db: String,
    val user: String,
    val password: String,
) {
    val jdbcUrl: String get() = "jdbc:ch://$host:$port/$db?socket_timeout=10000"
}

data class Argon2Config(
    val pepper: String,
)

data class OtelConfig(
    val serviceName: String,
    val otlpEndpoint: String,
)

fun Application.loadAppConfig(): AppConfig {
    val cfg = environment.config.config("stockyard")
    val pg = cfg.config("postgres")
    val redis = cfg.config("redis")
    val ch = cfg.config("clickhouse")
    val argon = cfg.config("argon2")
    val otel = cfg.config("otel")
    return AppConfig(
        postgres = PostgresConfig(
            host = pg.property("host").getString(),
            port = pg.property("port").getString().toInt(),
            db = pg.property("db").getString(),
            user = pg.property("user").getString(),
            password = pg.property("password").getString(),
        ),
        redis = RedisConfig(
            url = redis.property("url").getString(),
            password = redis.property("password").getString(),
        ),
        clickhouse = ClickHouseConfig(
            host = ch.property("host").getString(),
            port = ch.property("port").getString().toInt(),
            db = ch.property("db").getString(),
            user = ch.property("user").getString(),
            password = ch.property("password").getString(),
        ),
        argon2 = Argon2Config(
            pepper = argon.property("pepper").getString(),
        ),
        otel = OtelConfig(
            serviceName = otel.property("serviceName").getString(),
            otlpEndpoint = otel.property("otlpEndpoint").getString(),
        ),
    )
}
