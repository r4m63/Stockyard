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
 * Synthetic writer of `quotes:*` (Redis HASH + PUBLISH) and `quotes_ticks` (ClickHouse)
 * for dev environments that cannot run the real Quotes Service (e.g. macOS without the
 * `/dev/stockyard` kernel module). Activated when `STOCKYARD_QUOTES_SOURCE=fixture`
 * (HOCON `stockyard.quotesSource = fixture`).
 *
 * Production / Linux dev with the C-driver must set `STOCKYARD_QUOTES_SOURCE=driver`
 * — Quotes Service becomes the single writer, and this fixture must NOT run.
 *
 * Contract on Redis matches the frozen C2 contract from TASK-009:
 *   HASH fields `ts (ISO-8601 UTC) / ts_ns / bid / ask / last / volume` (cents as text),
 *   PUBLISH payload — ADR-011 cents-JSON `bidCents/askCents/lastCents/volume`.
 *
 * See ADR-010 (this task) for the source-selection decision.
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

        val rng = Random(0)
        val seed = tickers.associateWith { ticker ->
            val basis = 10_000L + (ticker.hashCode().toLong() and 0xFFFF) * 5L
            val spread = 30L + rng.nextLong(50)
            val mid = basis
            Triple(mid - spread, mid + spread, mid)
        }
        writeAll(seed)
        log.atWarn()
            .addKeyValue("tickers.count", tickers.size)
            .addKeyValue("interval.sec", intervalSec)
            .addKeyValue("ch.enabled", chDs != null)
            .log("DevPriceFixture ACTIVE — synthetic quotes writer. Disable in prod via STOCKYARD_QUOTES_SOURCE=driver.")

        job = scope.launch {
            val state = seed.toMutableMap()
            while (isActive) {
                delay(intervalSec * 1_000)
                tickers.forEach { ticker ->
                    val (prevBid, prevAsk, _) = state.getValue(ticker)
                    val mid = (prevBid + prevAsk) / 2
                    val jitterAmount = (mid * jitterPercent / 100.0).toLong().coerceAtLeast(1)
                    val deltaSigned = Random.nextLong(-jitterAmount, jitterAmount + 1)
                    val newMid = (mid + deltaSigned).coerceAtLeast(100)
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
                        ps.setLong(6, 0L)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
            }
        }.onFailure { e ->
            log.warn("DevPriceFixture: ClickHouse insert failed, continuing (Redis already updated): {}", e.message)
        }
    }

    private fun centsToDecimal(cents: Long): BigDecimal =
        BigDecimal(cents).divide(BigDecimal(100), 4, java.math.RoundingMode.UNNECESSARY)
}
