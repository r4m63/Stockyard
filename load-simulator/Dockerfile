# syntax=docker/dockerfile:1.6
# Load Simulator (TASK-016). Использует application plugin → :installDist
# создаёт launcher + libs/, который мы переносим в JRE-only runtime.

FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY gradlew ./
COPY gradle/ ./gradle/
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY src/ ./src/
RUN ./gradlew installDist --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/build/install/load-simulator/ /app/
ENV PATH="/app/bin:${PATH}"
ENTRYPOINT ["/app/bin/load-simulator"]
