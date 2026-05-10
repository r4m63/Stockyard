# syntax=docker/dockerfile:1.6
# Multi-stage build для core-service.
# Идентичный паттерн с gateway-service (TASK-003): build → JRE runtime + OTel agent.

# ---------------------------------------------------------------------------
# Stage 1 — build fat jar через Ktor Gradle plugin
# ---------------------------------------------------------------------------
FROM gradle:8.10-jdk21-alpine AS build
WORKDIR /app
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ ./gradle/
COPY src/ ./src/
RUN gradle buildFatJar --no-daemon

# ---------------------------------------------------------------------------
# Stage 2 — runtime, JRE-alpine + OTel Java Agent
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl

WORKDIR /app

# Версия должна совпадать с libs.versions.toml `otel-instrumentation`.
# TODO(security): добавить --checksum=sha256:<sha> к ADD после фиксации
# конкретной версии артефакта в release notes OTel java instrumentation.
ARG OTEL_AGENT_VERSION=2.9.0
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar /otel.jar

COPY --from=build /app/build/libs/*-all.jar /app/core-service.jar

ENV JAVA_TOOL_OPTIONS="-javaagent:/otel.jar"
EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=8 \
    CMD curl -fsS http://localhost:8080/health/live || exit 1

ENTRYPOINT ["java", "-jar", "/app/core-service.jar"]
