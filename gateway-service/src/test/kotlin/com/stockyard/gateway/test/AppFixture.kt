package com.stockyard.gateway.test

import com.stockyard.gateway.module
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder

/**
 * Тестовый bootstrap Application: подменяет HOCON в памяти, чтобы IT могли
 * указать реальный URL Testcontainers Redis и валидный `JWT_SECRET`.
 */
fun ApplicationTestBuilder.installTestModule(
    redisUrl: String,
    jwtSecret: String = "this-is-a-test-secret-32-bytes-min-length",
    coreServiceBaseUrl: String = "http://localhost:1",   // намеренно недоступный — readiness покажет DOWN
) {
    environment {
        config = MapApplicationConfig(
            "stockyard.jwt.secret" to jwtSecret,
            "stockyard.jwt.issuer" to "stockyard-gateway",
            "stockyard.jwt.audience" to "stockyard-clients",
            "stockyard.jwt.accessTtlSeconds" to "900",
            "stockyard.jwt.refreshTtlSeconds" to "2592000",
            "stockyard.redis.url" to redisUrl,
            "stockyard.redis.password" to "",
            "stockyard.coreService.baseUrl" to coreServiceBaseUrl,
            "stockyard.coreService.connectTimeoutMs" to "200",
            "stockyard.coreService.requestTimeoutMs" to "500",
            "stockyard.otel.serviceName" to "gateway-service-test",
            "stockyard.otel.otlpEndpoint" to "http://localhost:14317",
        )
    }
    application { module() }
}
