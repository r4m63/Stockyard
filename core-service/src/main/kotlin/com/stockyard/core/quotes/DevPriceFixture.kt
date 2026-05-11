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
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * Временный writer `quotes:*` в Redis + `quotes_ticks` в ClickHouse на время
 * разработки до реализации Quotes Service (TASK-008). На старте Core Service
 * инициализирует HASH-ключи `quotes:{ticker}` для всех 50 тикеров из
 * `instruments` и каждые [intervalSec] секунд:
 *   1) обновляет HASH в Redis (для current quote),
 *   2) делает batch INSERT в ClickHouse `quotes_ticks` (MV сама пересчитает свечи).
 *
 * Включается флагом `STOCKYARD_DEV_FIXTURE=true` (HOCON `stockyard.devFixture.enabled`).
 * В prod-like окружении выключается — единственный writer там Quotes Service.
 *
 * HSET и PUBLISH следуют frozen C2 контракту из TASK-009: HASH-поля
 * `ts (ISO-8601 UTC) / ts_ns / bid / ask / last / volume` (cents-integer как
 * строки), PUBLISH payload — ADR-011 cents-JSON (`bidCents/askCents/lastCents`).
 *
 * TODO(TASK-011): удалить весь класс и wire-up в Application после интеграции
 * Quotes Service в docker-compose.
 */
class DevPriceFixture(
    private val redis: RedisModule,
    private val instrumentRepo: InstrumentRepository,
    private val pgDs: HikariDataSource,
    private val chDs: HikariDataSource?,
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
            .addKeyValue("ch.enabled", chDs != null)
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
        val nowInstant = Instant.now()
        val nowIso = DateTimeFormatter.ISO_INSTANT.format(nowInstant)
        val nowNs = nowInstant.epochSecond * 1_000_000_000L + nowInstant.nano

        // 1) Redis — current quote (HASH) + PUBLISH per ADR-011 cents-JSON.
        //    HSET fields match Quotes Service C2: ts / ts_ns / bid / ask / last / volume.
        //    Raw-string JSON: dev-only, ticker alphanumeric, остальные значения integer/ISO.
        redis.withCommandConnection { conn ->
            val sync = conn.sync()
            snapshot.forEach { (ticker, bidAskLast) ->
                val (bid, ask, last) = bidAskLast
                sync.hset(
                    "quotes:$ticker",
                    mapOf(
                        "ts" to nowIso,
                        "ts_ns" to nowNs.toString(),
                        "bid" to bid.toString(),
                        "ask" to ask.toString(),
                        "last" to last.toString(),
                        "volume" to "0",
                    ),
                )
                val payload =
                    """{"ticker":"$ticker","ts":"$nowIso","tsNs":$nowNs,""" +
                        """"bidCents":$bid,"askCents":$ask,"lastCents":$last,"volume":0}"""
                sync.publish("channel:quotes:$ticker", payload)
            }
        }

        // 2) ClickHouse — quotes_ticks (history). Batch INSERT по 50 тикеров.
        // Если CH недоступен — логируем и продолжаем (Redis уже обновлён).
        val ch = chDs ?: return
        runCatching {
            ch.connection.use { conn ->
                conn.prepareStatement(
                    "INSERT INTO quotes_ticks (ticker, ts, bid, ask, last, volume) VALUES (?, ?, ?, ?, ?, ?)",
                ).use { ps ->
                    snapshot.forEach { (ticker, bidAskLast) ->
                        val (bid, ask, last) = bidAskLast
                        ps.setString(1, ticker)
                        ps.setObject(2, Timestamp.from(nowInstant))
                        ps.setBigDecimal(3, centsToDecimal(bid))
                        ps.setBigDecimal(4, centsToDecimal(ask))
                        ps.setBigDecimal(5, centsToDecimal(last))
                        ps.setLong(6, 0L)  // dev-fixture: volume фейковый, реальный — из биржи в TASK-008
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
            }
        }.onFailure { e ->
            log.warn("DevPriceFixture: ClickHouse insert failed, continuing (Redis already updated): {}", e.message)
        }
    }

    /** `Long` cents → `Decimal(18,4)` рублей. Конверсия `/ 100` без потерь. */
    private fun centsToDecimal(cents: Long): BigDecimal =
        BigDecimal(cents).divide(BigDecimal(100), 4, java.math.RoundingMode.UNNECESSARY)
}
