package com.stockyard.sim

import io.azam.ulidj.ULID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.random.Random

/**
 * Один виртуальный юзер. Сценарий:
 *  1) register (со случайным email/password),
 *  2) startWs(subscribe на 5 тикеров),
 *  3) loop: portfolio → place BUY → place SELL → deposit (раз в 10 итераций),
 *     с рандомной задержкой между действиями.
 *
 * Все операции пишут метрики через [Metrics].
 */
class UserSession(
    private val cfg: SimConfig,
    private val seed: Int,
) {
    private val client = SimClient(cfg.gatewayUrl)
    private val email = "sim_user_${ULID.random().lowercase()}@stockyard.test"
    private val password = "Passw0rd!" + Random.nextInt(10_000, 99_999)
    private var iteration = 0

    suspend fun start(scope: CoroutineScope) {
        if (!registerWithRetry()) return
        val wsJob = client.startWs(scope, cfg.tickers)
        try {
            while (scope.isActive) {
                runIteration()
                val jitter = Random.nextLong(cfg.actionIntervalMillisMin, cfg.actionIntervalMillisMax)
                delay(jitter)
            }
        } finally {
            wsJob.cancel()
            client.close()
        }
    }

    private suspend fun registerWithRetry(): Boolean {
        val started = System.currentTimeMillis()
        val ok = client.register(email, password)
        Metrics.recordLatency("register", System.currentTimeMillis() - started)
        if (ok) {
            Metrics.inc("register.ok")
            return true
        }
        Metrics.inc("register.fail")
        return false
    }

    private suspend fun runIteration() {
        iteration++
        val ticker = cfg.tickers[Random.nextInt(cfg.tickers.size)]
        val side = if (Random.nextBoolean()) "BUY" else "SELL"

        val orderStart = System.currentTimeMillis()
        val orderStatus = client.placeOrder(ticker, side, cfg.orderQty)
        Metrics.recordLatency("order.${side.lowercase()}", System.currentTimeMillis() - orderStart)
        Metrics.inc(
            "order.${side.lowercase()}." + when (orderStatus) {
                in 200..299 -> "ok"
                422 -> "rejected"
                429 -> "rate_limited"
                else -> "fail_$orderStatus"
            },
        )

        if (iteration % 10 == 0) {
            val depStart = System.currentTimeMillis()
            val depStatus = client.deposit(cfg.depositAmountCents)
            Metrics.recordLatency("deposit", System.currentTimeMillis() - depStart)
            Metrics.inc("deposit." + if (depStatus in 200..299) "ok" else "fail_$depStatus")
        }
        if (iteration % 5 == 0) {
            val pfStart = System.currentTimeMillis()
            val pfStatus = client.portfolio()
            Metrics.recordLatency("portfolio", System.currentTimeMillis() - pfStart)
            Metrics.inc("portfolio." + if (pfStatus in 200..299) "ok" else "fail_$pfStatus")
        }
    }
}

/** Launch single-shot session (для тестов). */
fun runSingleSession(cfg: SimConfig) = runBlocking {
    val session = UserSession(cfg, seed = 0)
    val scope = this
    val job = scope.launch { session.start(scope) }
    delay(5_000)
    job.cancel()
}
