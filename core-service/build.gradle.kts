plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    application
}

tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    mergeServiceFiles()
}

group = "com.stockyard"
version = "0.2.0"

application {
    mainClass.set("com.stockyard.core.ApplicationKt")
}

ktor {
    fatJar {
        archiveFileName.set("core-service-${project.version}-all.jar")
    }
}

dependencies {
    // Ktor server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.metrics.micrometer)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Kotlinx
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    // Logging
    implementation(libs.logback.classic)
    implementation(libs.logstash.encoder)

    // PostgreSQL + HikariCP + Flyway (per 12-storage-operations §12.1)
    implementation(libs.postgresql)
    implementation(libs.hikari)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)

    // ClickHouse JDBC (для GET /quotes/{ticker}/history — TASK-008)
    implementation(libs.clickhouse.jdbc)

    // Redis (Lettuce + commons-pool2 для GenericObjectPool, как в gateway)
    implementation(libs.lettuce.core)
    implementation(libs.commons.pool2)

    // Argon2id для хэширования паролей (ADR-006)
    implementation(libs.argon2)

    // ULID для идентификаторов user/order (CLAUDE.md «Конвенции/Идентификаторы»)
    implementation(libs.ulidj)

    // Prometheus exporter через Micrometer + Ktor MicrometerMetrics plugin
    implementation(libs.micrometer.prometheus)

    // OpenTelemetry (SDK; Java Agent делает heavy lifting через JAVA_TOOL_OPTIONS)
    implementation(platform(libs.otel.bom))
    implementation(libs.otel.api)
    implementation(libs.otel.sdk)
    implementation(libs.otel.exporter.otlp)

    // Tests (используются /tester)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.mockk)
    testImplementation(libs.awaitility.kotlin)
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.ktor.server.test.host)
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
