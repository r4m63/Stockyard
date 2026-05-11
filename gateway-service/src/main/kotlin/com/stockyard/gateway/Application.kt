package com.stockyard.gateway

import com.stockyard.gateway.auth.AuthService
import com.stockyard.gateway.auth.JwtVerifiers
import com.stockyard.gateway.auth.SessionStore
import com.stockyard.gateway.client.CoreServiceClient
import com.stockyard.gateway.config.installPlugins
import com.stockyard.gateway.config.loadAppConfig
import com.stockyard.gateway.redis.RedisModule
import com.stockyard.gateway.routing.authRoutes
import com.stockyard.gateway.routing.healthRoutes
import com.stockyard.gateway.routing.instrumentsRoutes
import com.stockyard.gateway.routing.ordersRoutes
import com.stockyard.gateway.routing.portfolioRoutes
import com.stockyard.gateway.routing.quotesRoutes
import com.stockyard.gateway.ws.QuotesSubscriber
import com.stockyard.gateway.ws.WsHub
import com.stockyard.gateway.ws.WsMetrics
import com.stockyard.gateway.ws.wsRoutes
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.netty.EngineMain
import io.ktor.server.routing.routing
import io.ktor.websocket.CloseReason
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("Application")

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    val config = loadAppConfig()

    // Fail-fast если JWT_SECRET не задан или слишком короткий.
    // HS256 требует минимум 32 байта энтропии; пустой/короткий секрет — это
    // прямой обход auth после TASK-005. См. /reviewer TASK-003 finding H1.
    require(config.jwt.secret.length >= 32) {
        "JWT_SECRET must be at least 32 characters. Set JWT_SECRET environment variable " +
            "(generate via: openssl rand -base64 32)."
    }

    log.atInfo()
        .addKeyValue("service.name", config.otel.serviceName)
        .log("Bootstrapping gateway-service")

    val redis = RedisModule(config.redis)
    val coreClient = CoreServiceClient(config.coreService)
    val jwtVerifiers = JwtVerifiers(config.jwt)
    val sessionStore = SessionStore(redis)
    val authService = AuthService(coreClient, jwtVerifiers, sessionStore, config.jwt)
    val wsMetrics = WsMetrics()
    val wsHub = WsHub(wsMetrics)
    val quotesSubscriber = QuotesSubscriber(redis, wsHub, wsMetrics)

    environment.monitor.subscribe(ApplicationStopping) {
        log.info("Shutdown: draining WS, stopping Pub/Sub, closing Redis and Core")
        runCatching { runBlocking { wsHub.closeAll(CloseReason.Codes.GOING_AWAY.code) } }
        runCatching { quotesSubscriber.stop() }
        runCatching { redis.close() }
        runCatching { coreClient.close() }
    }

    installPlugins(jwtVerifiers)
    quotesSubscriber.start()

    routing {
        healthRoutes(redis, coreClient)
        authRoutes(authService)
        ordersRoutes(coreClient)
        portfolioRoutes(coreClient)
        instrumentsRoutes(coreClient)
        quotesRoutes(coreClient)
        wsRoutes(wsHub, jwtVerifiers, sessionStore, wsMetrics, redis)
    }
}
