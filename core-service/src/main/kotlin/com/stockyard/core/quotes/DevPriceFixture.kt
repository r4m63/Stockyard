package com.stockyard.core.quotes

import com.stockyard.core.domain.instrument.InstrumentRepository
import com.stockyard.core.redis.RedisModule
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Instant
import kotlin.random.Random

/**
 * Временный writer `quotes:*` в Redis на время разработки до реализации
 * Quotes Service (TASK-008). На старте Core Service инициализирует HASH-ключи
 * `quotes:{ticker}` для всех 50 тикеров из `instruments` и каждые [intervalSec]
 * секунд делает random walk цен (±[jitterPercent]).
 *
 * Включается флагом `STOCKYARD_DEV_FIXTURE=true` (HOCON `stockyard.devFixture.enabled`).
 * В prod-like окружении должно быть выключено — единственный writer там Quotes Service.
 *
 * TODO(TASK-008): удалить весь класс и wire-up в Application после реализации
 * Driver → Quotes Service → Redis-pipeline.
 */
class DevPriceFixture(
    private val redis: RedisModule,
    private val instrumentRepo: InstrumentRepository,
    private val pgDs: HikariDataSource,
    private val intervalSec: Long,
    private val jitterPercent: Double,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    fun start() {
        val tickers = pgDs.connection.use { conn -> instrumentRepo.listTickers(conn) }
        if (tickers.isEmpty()) {
            log.warn("DevPriceFixture: no instruments in catalogue, skipping fixture")
            return
        }

        // Начальные цены — детерминированные через hash тикера, чтобы перезапуски
        // давали стартово одни и те же значения (повторяемость тестов).
        val rng = Random(0)
        val seed = tickers.associateWith { ticker ->
            val basis = 10_000L + (ticker.hashCode().toLong() and 0xFFFF) * 5L  // 10к..~340к копеек
            val spread = 30L + rng.nextLong(50)
            val mid = basis
            Triple(mid - spread, mid + spread, mid)  // bid, ask, last
        }
        writeAll(seed)
        log.atInfo()
            .addKeyValue("tickers.count", tickers.size)
            .addKeyValue("interval.sec", intervalSec)
            .log("DevPriceFixture initialized")

        job = scope.launch {
            val state = seed.toMutableMap()
            while (isActive) {
                delay(intervalSec * 1_000)
                tickers.forEach { ticker ->
                    val (prevBid, prevAsk, _) = state.getValue(ticker)
                    val mid = (prevBid + prevAsk) / 2
                    val jitterAmount = (mid * jitterPercent / 100.0).toLong().coerceAtLeast(1)
                    val deltaSigned = Random.nextLong(-jitterAmount, jitterAmount + 1)
                    val newMid = (mid + deltaSigned).coerceAtLeast(100)  // не позволяем уйти в копейки
                    val spread = (prevAsk - prevBid).coerceAtLeast(10)
                    val newBid = newMid - spread / 2
                    val newAsk = newMid + spread / 2
                    state[ticker] = Triple(newBid, newAsk, newMid)
                }
                writeAll(state)
            }
        }
    }

    fun stop() {
        runCatching { job?.cancel() }
        runCatching { scope.cancel() }
    }

    private fun writeAll(snapshot: Map<String, Triple<Long, Long, Long>>) {
        val now = Instant.now().toEpochMilli().toString()
        redis.withCommandConnection { conn ->
            val sync = conn.sync()
            snapshot.forEach { (ticker, bidAskLast) ->
                val (bid, ask, last) = bidAskLast
                sync.hset(
                    "quotes:$ticker",
                    mapOf("bid" to bid.toString(), "ask" to ask.toString(), "last" to last.toString(), "ts" to now),
                )
            }
        }
    }
}
