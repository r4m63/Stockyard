package com.stockyard.core.quotes

import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

/**
 * Чтение OHLC-свечей из ClickHouse через materialized views `quotes_candles_1m`
 * и `quotes_candles_1h` (см. 06-data §6.4.2). Для merge-агрегатов используются
 * `argMinMerge` / `argMaxMerge` / `maxMerge` / `minMerge` / `sumMerge`.
 *
 * В CH цена хранится как `Decimal(18,4)` (рубли с 4 знаками после запятой);
 * на API уровне отдаём `Long` cents, конверсия `× 100`.
 */
class CandlesRepository(private val ch: HikariDataSource) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Возвращает свечи [from..to] по тикеру с заданным интервалом.
     * I/O на Dispatchers.IO, чтобы Ktor-coroutines не блокировались.
     */
    suspend fun loadCandles(
        ticker: String,
        from: Instant,
        to: Instant,
        interval: CandleInterval,
    ): List<Candle> = withContext(Dispatchers.IO) {
        val (table, tsColumn) = when (interval) {
            CandleInterval.M1 -> "quotes_candles_1m" to "ts_minute"
            CandleInterval.H1 -> "quotes_candles_1h" to "ts_hour"
        }
        val sql = """
            SELECT $tsColumn AS ts,
                   argMinMerge(open)  AS open,
                   argMaxMerge(close) AS close,
                   maxMerge(high)     AS high,
                   minMerge(low)      AS low,
                   sumMerge(volume)   AS volume
            FROM $table
            WHERE ticker = ? AND $tsColumn BETWEEN ? AND ?
            GROUP BY $tsColumn
            ORDER BY $tsColumn
        """.trimIndent()
        ch.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, ticker)
                ps.setObject(2, Timestamp.from(from))
                ps.setObject(3, Timestamp.from(to))
                ps.executeQuery().use { rs ->
                    val acc = mutableListOf<Candle>()
                    while (rs.next()) acc += mapRow(rs)
                    log.atDebug()
                        .addKeyValue("ticker", ticker)
                        .addKeyValue("interval", interval.name)
                        .addKeyValue("rows", acc.size)
                        .log("candles loaded")
                    acc
                }
            }
        }
    }

    private fun mapRow(rs: ResultSet): Candle = Candle(
        tsEpochMs = rs.getTimestamp("ts").toInstant().toEpochMilli(),
        openCents = decimalToCents(rs.getBigDecimal("open")),
        highCents = decimalToCents(rs.getBigDecimal("high")),
        lowCents = decimalToCents(rs.getBigDecimal("low")),
        closeCents = decimalToCents(rs.getBigDecimal("close")),
        volume = rs.getLong("volume"),
    )

    /** `Decimal(18,4)` рублей → `Long` cents через × 100. */
    private fun decimalToCents(value: BigDecimal?): Long =
        value?.multiply(BigDecimal(100))?.setScale(0, java.math.RoundingMode.HALF_UP)?.toLong() ?: 0L
}

enum class CandleInterval { M1, H1 }

data class Candle(
    val tsEpochMs: Long,
    val openCents: Long,
    val highCents: Long,
    val lowCents: Long,
    val closeCents: Long,
    val volume: Long,
)
