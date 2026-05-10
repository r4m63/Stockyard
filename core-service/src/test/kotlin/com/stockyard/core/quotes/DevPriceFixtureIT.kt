package com.stockyard.core.quotes

import com.stockyard.core.config.ClickHouseConfig
import com.stockyard.core.config.PostgresConfig
import com.stockyard.core.config.RedisConfig
import com.stockyard.core.domain.instrument.InstrumentRepository
import com.stockyard.core.persistence.DataSources
import com.stockyard.core.persistence.FlywayBootstrap
import com.stockyard.core.redis.RedisModule
import com.stockyard.core.test.PgFixture
import com.stockyard.core.test.RedisFixture
import io.kotest.matchers.shouldBe
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DevPriceFixtureIT {

    private val pg: PostgreSQLContainer<*> = PgFixture.container()
    private val redis: GenericContainer<*> = RedisFixture.container()
    private val redisUrl get() = "redis://${redis.host}:${redis.firstMappedPort}"

    private lateinit var dataSources: DataSources
    private lateinit var redisModule: RedisModule
    private var fixture: DevPriceFixture? = null

    @BeforeAll
    fun setUp() {
        pg.start()
        redis.start()
        dataSources = DataSources(
            PostgresConfig(pg.host, pg.firstMappedPort, "stockyard", pg.username, pg.password),
            ClickHouseConfig("localhost", 65535, "stockyard", "stockyard", "stockyard"),
        )
        FlywayBootstrap.migrate(dataSources.pg)
        redisModule = RedisModule(RedisConfig(redisUrl, ""))
    }

    @AfterAll
    fun tearDown() {
        runCatching { fixture?.stop() }
        runCatching { redisModule.close() }
        runCatching { dataSources.close() }
        pg.stop()
        redis.stop()
    }

    @Test
    fun `start populates HASH for every instrument with bid ask last ts fields`() {
        fixture = DevPriceFixture(
            redis = redisModule,
            instrumentRepo = InstrumentRepository(),
            pgDs = dataSources.pg,
            intervalSec = 1,
            jitterPercent = 0.5,
        ).also { it.start() }

        // Сразу после start — initial seed уже записан.
        val sber = redisModule.withCommandConnection { it.sync().hgetall("quotes:SBER") }
        sber.keys shouldBe setOf("bid", "ask", "last", "ts")
        val bid = sber["bid"]!!.toLong()
        val ask = sber["ask"]!!.toLong()
        val ts = sber["ts"]!!.toLong()
        (bid > 0) shouldBe true
        (ask > bid) shouldBe true
        (ts > 0) shouldBe true
    }

    @Test
    fun `random walk changes prices within the configured jitter`() {
        // initial set уже сделан в предыдущем тесте; считаем «before» и ждём изменение.
        val before = redisModule.withCommandConnection { it.sync().hget("quotes:SBER", "last") }!!.toLong()

        await.atMost(Duration.ofSeconds(3)).untilAsserted {
            val now = redisModule.withCommandConnection { it.sync().hget("quotes:SBER", "last") }!!.toLong()
            (now != before) shouldBe true
            // ±5% от before — щедрая граница (jitter=0.5%, walk шаг = 0.5%).
            val drift = kotlin.math.abs(now - before)
            (drift < before / 20) shouldBe true
        }
    }
}
