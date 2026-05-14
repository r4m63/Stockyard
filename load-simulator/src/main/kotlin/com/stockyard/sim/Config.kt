package com.stockyard.sim

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Конфиг symulatora (TASK-016). Читается из env переменных.
 */
data class SimConfig(
    val gatewayUrl: String = env("SIM_GATEWAY_URL", "http://localhost:8080"),
    val users: Int = envInt("SIM_USERS", 100),
    val rampDuration: Duration = envInt("SIM_RAMP_SECONDS", 10).seconds,
    val holdDuration: Duration = envInt("SIM_HOLD_SECONDS", 60).seconds,
    val actionIntervalMillisMin: Long = envInt("SIM_ACTION_MIN_MS", 1000).toLong(),
    val actionIntervalMillisMax: Long = envInt("SIM_ACTION_MAX_MS", 5000).toLong(),
    val depositAmountCents: Long = envInt("SIM_DEPOSIT_CENTS", 1_000_000).toLong(), // 10k RUB
    val orderQty: Int = envInt("SIM_ORDER_QTY", 1),
    val printIntervalSeconds: Int = envInt("SIM_PRINT_SECONDS", 5),
    val tickers: List<String> = env("SIM_TICKERS", "SBER,GAZP,LKOH,YNDX,VTBR").split(",").map { it.trim() }.filter { it.isNotEmpty() },
)

private fun env(name: String, default: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

private fun envInt(name: String, default: Int): Int =
    System.getenv(name)?.toIntOrNull() ?: default
