package com.stockyard.core.test

import com.stockyard.core.module
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder

/**
 * Bootstrap Application для testApplication { } блоков.
 * Подменяет HOCON in-memory: указывает Testcontainers Redis URL, тестовый
 * PG host/port/credentials, заведомо невалидный CH host (для info-only checks).
 */
fun ApplicationTestBuilder.installTestModule(
    pgHost: String,
    pgPort: Int,
    pgDb: String = "stockyard",
    pgUser: String = "stockyard",
    pgPassword: String = "stockyard",
    redisUrl: String,
    redisPassword: String = "",
    chHost: String = "localhost",
    chPort: Int = 65535,           // намеренно закрыт, info-only check вернёт DOWN
    chDb: String = "stockyard",
    chUser: String = "stockyard",
    chPassword: String = "stockyard",
    argon2Pepper: String = "this-is-a-test-pepper-32-bytes-min-length",
) {
    environment {
        config = MapApplicationConfig(
            "stockyard.postgres.host"     to pgHost,
            "stockyard.postgres.port"     to pgPort.toString(),
            "stockyard.postgres.db"       to pgDb,
            "stockyard.postgres.user"     to pgUser,
            "stockyard.postgres.password" to pgPassword,
            "stockyard.redis.url"         to redisUrl,
            "stockyard.redis.password"    to redisPassword,
            "stockyard.clickhouse.host"   to chHost,
            "stockyard.clickhouse.port"   to chPort.toString(),
            "stockyard.clickhouse.db"     to chDb,
            "stockyard.clickhouse.user"   to chUser,
            "stockyard.clickhouse.password" to chPassword,
            "stockyard.argon2.pepper"     to argon2Pepper,
            "stockyard.otel.serviceName"  to "core-service-test",
            "stockyard.otel.otlpEndpoint" to "http://localhost:14317",
        )
    }
    application { module() }
}
