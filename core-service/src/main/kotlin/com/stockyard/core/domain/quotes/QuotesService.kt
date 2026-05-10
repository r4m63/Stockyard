package com.stockyard.core.domain.quotes

import com.stockyard.core.domain.instrument.Instrument
import com.stockyard.core.domain.instrument.InstrumentRepository
import com.stockyard.core.persistence.DataSources
import com.stockyard.core.quotes.Candle
import com.stockyard.core.quotes.CandleInterval
import com.stockyard.core.quotes.CandlesRepository
import com.stockyard.core.quotes.Quote
import com.stockyard.core.quotes.QuotesPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

/**
 * Application-service для эндпоинтов котировок: текущая цена, история свечей,
 * каталог инструментов. Read-only по PG + Redis + ClickHouse.
 */
class QuotesService(
    private val dataSources: DataSources,
    private val instrumentRepo: InstrumentRepository,
    private val quotesPort: QuotesPort,
    private val candlesRepo: CandlesRepository,
) {

    /** Текущая цена + bid/ask/last/ts. */
    suspend fun getQuote(ticker: String): Quote {
        if (!tickerExists(ticker)) throw InstrumentNotFoundException(ticker)
        return quotesPort.getQuote(ticker)
            ?: throw com.stockyard.core.domain.order.NoQuoteAvailableException(ticker)
    }

    suspend fun getHistory(
        ticker: String,
        from: Instant,
        to: Instant,
        intervalRaw: String,
    ): HistoryResult {
        if (!tickerExists(ticker)) throw InstrumentNotFoundException(ticker)
        val interval = parseInterval(intervalRaw)
        validateRange(from, to, interval)
        val candles = candlesRepo.loadCandles(ticker, from, to, interval)
        return HistoryResult(ticker = ticker, interval = interval, candles = candles)
    }

    suspend fun listInstruments(): List<Instrument> = withContext(Dispatchers.IO) {
        dataSources.pg.connection.use { conn -> instrumentRepo.listAll(conn) }
    }

    private suspend fun tickerExists(ticker: String): Boolean = withContext(Dispatchers.IO) {
        dataSources.pg.connection.use { conn -> instrumentRepo.existsTicker(conn, ticker) }
    }

    private fun parseInterval(raw: String): CandleInterval = when (raw.lowercase()) {
        "1m" -> CandleInterval.M1
        "1h" -> CandleInterval.H1
        else -> throw InvalidIntervalException(raw)
    }

    private fun validateRange(from: Instant, to: Instant, interval: CandleInterval) {
        if (!from.isBefore(to)) throw InvalidTimeRangeException("from must be < to")
        val span = Duration.between(from, to)
        val limit = when (interval) {
            CandleInterval.M1 -> MAX_M1_SPAN
            CandleInterval.H1 -> MAX_H1_SPAN
        }
        if (span > limit) {
            throw InvalidTimeRangeException("span exceeds ${limit.toDays()}d limit for ${interval.name}")
        }
    }

    companion object {
        val MAX_M1_SPAN: Duration = Duration.ofDays(7)
        val MAX_H1_SPAN: Duration = Duration.ofDays(90)
    }
}

data class HistoryResult(
    val ticker: String,
    val interval: CandleInterval,
    val candles: List<Candle>,
)
