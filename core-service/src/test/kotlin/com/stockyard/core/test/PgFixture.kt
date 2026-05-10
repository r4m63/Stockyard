package com.stockyard.core.test

import org.testcontainers.containers.PostgreSQLContainer

/**
 * Testcontainers PostgreSQL для интеграционных тестов.
 * Образ совпадает с production (postgres:16-alpine, см. docker-compose.yml).
 */
object PgFixture {
    fun container(): PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("stockyard")
        .withUsername("stockyard")
        .withPassword("stockyard")
}
