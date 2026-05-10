plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    application
}

group = "com.stockyard"
version = "0.1.0"

application {
    mainClass.set("com.stockyard.gateway.ApplicationKt")
}

ktor {
    fatJar {
        archiveFileName.set("gateway-service-${project.version}-all.jar")
    }
}

dependencies {
    // Ktor server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Ktor client (для CoreServiceClient)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)

    // Kotlinx
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    // Logging
    implementation(libs.logback.classic)
    implementation(libs.logstash.encoder)

    // Auth (JWT issue/verify)
    implementation(libs.java.jwt)

    // Redis (Lettuce + commons-pool2 для GenericObjectPool — см. 12-storage-operations §12.2.3)
    implementation(libs.lettuce.core)
    implementation(libs.commons.pool2)

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
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.ktor.client.content.negotiation.test)
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.junit)
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
