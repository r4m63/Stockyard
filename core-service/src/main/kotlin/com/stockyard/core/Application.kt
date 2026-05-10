package com.stockyard.core

import com.stockyard.core.api.instrumentApi
import com.stockyard.core.api.orderApi
import com.stockyard.core.api.portfolioApi
import com.stockyard.core.api.quotesApi
import com.stockyard.core.api.userApi
import com.stockyard.core.auth.PasswordHasher
import com.stockyard.core.config.installPlugins
import com.stockyard.core.config.loadAppConfig
import com.stockyard.core.domain.account.AccountRepository
import com.stockyard.core.domain.instrument.InstrumentRepository
import com.stockyard.core.domain.order.OrderRepository
import com.stockyard.core.domain.order.OrderService
import com.stockyard.core.domain.position.PositionRepository
import com.stockyard.core.domain.transaction.TransactionRepository
import com.stockyard.core.domain.user.UserRepository
import com.stockyard.core.domain.user.UserService
import com.stockyard.core.persistence.DataSources
import com.stockyard.core.persistence.FlywayBootstrap
import com.stockyard.core.persistence.TransactionManager
import com.stockyard.core.quotes.DevPriceFixture
import com.stockyard.core.quotes.QuotesPort
import com.stockyard.core.redis.RedisModule
import com.stockyard.core.routing.healthRoutes
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.netty.EngineMain
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("Application")

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    val config = loadAppConfig()

    // Fail-fast валидация секретов (по аналогии с reviewer finding H1 в TASK-003).
    require(config.argon2.pepper.toByteArray(Charsets.UTF_8).size >= PasswordHasher.PEPPER_MIN_BYTES) {
        "ARGON2_PEPPER must be at least ${PasswordHasher.PEPPER_MIN_BYTES} bytes. " +
            "Set ARGON2_PEPPER environment variable (generate via: openssl rand -base64 32)."
    }
    require(config.postgres.password.isNotEmpty()) {
        "PG_PASSWORD must be set (no default for production safety)."
    }

    log.atInfo()
        .addKeyValue("service.name", config.otel.serviceName)
        .addKeyValue("pg.host", config.postgres.host)
        .addKeyValue("redis.url", config.redis.url)
        .addKeyValue("devFixture.enabled", config.devFixture.enabled)
        .log("Bootstrapping core-service")

    val dataSources = DataSources(config.postgres, config.clickhouse)
    val redis = RedisModule(config.redis)
    val passwordHasher = PasswordHasher(config.argon2.pepper.toByteArray(Charsets.UTF_8))
    val txManager = TransactionManager(dataSources.pg)

    // Repositories — все stateless, делятся через DI.
    val userRepo = UserRepository()
    val instrumentRepo = InstrumentRepository()
    val orderRepo = OrderRepository()
    val positionRepo = PositionRepository()
    val accountRepo = AccountRepository()
    val transactionRepo = TransactionRepository()

    val userService = UserService(userRepo, txManager, passwordHasher)
    val quotesPort = QuotesPort(redis)
    val orderService = OrderService(
        tx = txManager,
        instruments = instrumentRepo,
        orders = orderRepo,
        accounts = accountRepo,
        positions = positionRepo,
        transactions = transactionRepo,
        quotes = quotesPort,
    )

    // Flyway migration ДО открытия HTTP-сокета. Падение здесь = провал старта Ktor.
    FlywayBootstrap.migrate(dataSources.pg)

    // TODO(TASK-008): удалить DevPriceFixture после реализации Quotes Service.
    val devFixture: DevPriceFixture? = if (config.devFixture.enabled) {
        DevPriceFixture(
            redis = redis,
            instrumentRepo = instrumentRepo,
            pgDs = dataSources.pg,
            intervalSec = config.devFixture.intervalSec,
            jitterPercent = config.devFixture.jitterPercent,
        ).also { it.start() }
    } else {
        log.info("DevPriceFixture disabled (production-like mode)")
        null
    }

    monitor.subscribe(ApplicationStopping) {
        log.info("Shutdown: closing DevPriceFixture, DataSources and Redis connections")
        runCatching { devFixture?.stop() }
        runCatching { dataSources.close() }
        runCatching { redis.close() }
    }

    val prometheusRegistry = installPlugins()

    routing {
        healthRoutes(dataSources, redis, prometheusRegistry)
        userApi(userService)
        orderApi(orderService)
        portfolioApi()
        instrumentApi()
        quotesApi()
    }
}
