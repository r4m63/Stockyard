package com.stockyard.sim

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("LoadSimulator")

/**
 * Entry-point Load Simulator (TASK-016). Запуск: `./gradlew run` или fat jar.
 *
 * Конфиг через env:
 *   SIM_GATEWAY_URL=http://localhost:8080
 *   SIM_USERS=100
 *   SIM_RAMP_SECONDS=10
 *   SIM_HOLD_SECONDS=60
 *
 * Ramp: линейный подъём от 0 до N юзеров за SIM_RAMP_SECONDS, hold на SIM_HOLD_SECONDS,
 * graceful drain через job.cancelAndJoin. Метрики печатаются каждые SIM_PRINT_SECONDS.
 */
fun main() = runBlocking {
    val cfg = SimConfig()
    log.info(
        "Load Simulator: users={} gateway={} ramp={}s hold={}s tickers={}",
        cfg.users, cfg.gatewayUrl, cfg.rampDuration.inWholeSeconds, cfg.holdDuration.inWholeSeconds, cfg.tickers,
    )

    val supervisor = CoroutineScope(Dispatchers.IO)
    val jobs = mutableListOf<Job>()

    val printer = supervisor.launch {
        while (isActive) {
            delay(cfg.printIntervalSeconds * 1_000L)
            print(Metrics.snapshot())
        }
    }

    val rampStepMs = if (cfg.users > 0) cfg.rampDuration.inWholeMilliseconds / cfg.users else 0L
    repeat(cfg.users) { i ->
        val session = UserSession(cfg, seed = i)
        jobs += supervisor.launch { session.start(supervisor) }
        if (rampStepMs > 0) delay(rampStepMs)
    }

    log.info("Ramp complete, holding for {}s", cfg.holdDuration.inWholeSeconds)
    delay(cfg.holdDuration.inWholeMilliseconds)

    log.info("Draining {} sessions", jobs.size)
    jobs.forEach { it.cancel() }
    printer.cancel()
    supervisor.cancel()

    print(Metrics.snapshot())
    log.info("Load Simulator finished")
}
