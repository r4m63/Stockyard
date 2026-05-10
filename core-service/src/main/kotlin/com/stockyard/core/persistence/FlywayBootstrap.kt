package com.stockyard.core.persistence

import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import javax.sql.DataSource

/**
 * Programmatic Flyway migration на старте Application.module().
 * Запускается ДО открытия HTTP-сокета — гарантирует, что схема актуальна
 * прежде чем поступят первые запросы. См. 12-storage-operations §12.1.4.
 *
 * Если миграция падает — exception пробрасывается выше, Ktor не стартует.
 * Это правильный fail-fast: лучше пусть deploy упадёт, чем сервис будет
 * крутиться на старой/несогласованной схеме.
 */
object FlywayBootstrap {

    private val log = LoggerFactory.getLogger(FlywayBootstrap::class.java)

    fun migrate(dataSource: DataSource) {
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .validateOnMigrate(true)
            .cleanDisabled(true)
            .outOfOrder(false)
            .load()

        val result = flyway.migrate()

        log.atInfo()
            .addKeyValue("migrations.applied", result.migrationsExecuted)
            .addKeyValue("schema.version", result.targetSchemaVersion?.version ?: "(none)")
            .addKeyValue("initial.schema.version", result.initialSchemaVersion?.version ?: "(empty)")
            .log("Flyway migration complete")
    }
}
