package com.stockyard.core.quotes

import com.stockyard.core.redis.RedisModule
import org.slf4j.LoggerFactory

/**
 * Чтение текущих котировок из Redis. Цена берётся **до** старта PG-транзакции
 * (CLAUDE.md «Деньги»), чтобы не держать TX открытой во время сетевого вызова.
 *
 * Ключи: `quotes:{ticker}` HASH с полями `bid`, `ask`, `last` (cents, Long как строка)
 * и `ts` (epochMs). См. [docs/architecture/06-data.md §6.3.2].
 *
 * Source цен в MVP — [DevPriceFixture] (до TASK-008 — Quotes Service).
 */
class QuotesPort(private val redis: RedisModule) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Цена `ask` (для BUY) в копейках. null если ключа/поля нет. */
    fun getAsk(ticker: String): Long? = readField(ticker, "ask")

    /** Цена `bid` (для SELL) в копейках. null если ключа/поля нет. */
    fun getBid(ticker: String): Long? = readField(ticker, "bid")

    private fun readField(ticker: String, field: String): Long? {
        val raw = redis.withCommandConnection { it.sync().hget(key(ticker), field) } ?: return null
        return raw.toLongOrNull().also {
            if (it == null) log.warn("malformed quote field {}.{}={}", ticker, field, raw)
        }
    }

    private fun key(ticker: String) = "quotes:$ticker"
}
