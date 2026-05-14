package com.stockyard.sim

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Простые in-process метрики. Хранит:
 *  - count per (op, status) — "register.ok", "register.fail", "buy.rejected_funds" и т.д.
 *  - реестр latencies (ms) для каждой операции — отбираем p50/p95/p99 на снэпшоте.
 *
 * Никакого Prometheus — slim для MVP. Реальные метрики сервисов в Prometheus.
 */
object Metrics {
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val latencies = ConcurrentHashMap<String, MutableList<Long>>()

    fun inc(name: String, by: Long = 1) {
        counters.computeIfAbsent(name) { AtomicLong(0) }.addAndGet(by)
    }

    fun recordLatency(op: String, ms: Long) {
        latencies.computeIfAbsent(op) { synchronizedList() }.also { list ->
            synchronized(list) { list.add(ms) }
        }
    }

    private fun synchronizedList(): MutableList<Long> = java.util.Collections.synchronizedList(ArrayList(1024))

    fun snapshot(): String {
        val sb = StringBuilder()
        sb.appendLine("--- Sim Metrics ---")
        val sorted = counters.entries.sortedBy { it.key }
        for ((name, value) in sorted) {
            sb.appendLine("count.$name=${value.get()}")
        }
        for ((op, list) in latencies.entries.sortedBy { it.key }) {
            val copy = synchronized(list) { list.toLongArray() }
            if (copy.isEmpty()) continue
            copy.sort()
            val p50 = pct(copy, 50)
            val p95 = pct(copy, 95)
            val p99 = pct(copy, 99)
            sb.appendLine("latency.$op.ms p50=$p50 p95=$p95 p99=$p99 n=${copy.size}")
        }
        sb.appendLine("------------------")
        return sb.toString()
    }

    private fun pct(sortedAsc: LongArray, percentile: Int): Long {
        if (sortedAsc.isEmpty()) return -1
        val rank = (percentile / 100.0 * (sortedAsc.size - 1)).toInt()
        return sortedAsc[rank.coerceIn(0, sortedAsc.size - 1)]
    }
}
