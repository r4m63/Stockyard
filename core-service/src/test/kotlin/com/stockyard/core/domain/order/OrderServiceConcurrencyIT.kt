package com.stockyard.core.domain.order

import com.stockyard.core.auth.PasswordHasher
import com.stockyard.core.domain.account.AccountRepository
import com.stockyard.core.domain.instrument.InstrumentRepository
import com.stockyard.core.domain.position.PositionRepository
import com.stockyard.core.domain.transaction.TransactionRepository
import com.stockyard.core.domain.user.UserRepository
import com.stockyard.core.domain.user.UserService
import com.stockyard.core.persistence.DataSources
import com.stockyard.core.persistence.FlywayBootstrap
import com.stockyard.core.persistence.TransactionManager
import com.stockyard.core.quotes.QuotesPort
import com.stockyard.core.redis.RedisModule
import com.stockyard.core.test.PgFixture
import com.stockyard.core.test.RedisFixture
import com.stockyard.core.config.ClickHouseConfig
import com.stockyard.core.config.PostgresConfig
import com.stockyard.core.config.RedisConfig
import io.kotest.matchers.shouldBe
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Concurrent-сценарии — race на FOR UPDATE accounts/orders.UNIQUE.
 * Прямо инстанцируем зависимости без Ktor (быстрее, чем testApplication).
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderServiceConcurrencyIT {

    private val pg: PostgreSQLContainer<*> = PgFixture.container()
    private val redis: GenericContainer<*> = RedisFixture.container()
    private val redisUrl get() = "redis://${redis.host}:${redis.firstMappedPort}"

    private lateinit var dataSources: DataSources
    private lateinit var redisModule: RedisModule
    private lateinit var service: OrderService
    private lateinit var userService: UserService
    private lateinit var redisClient: RedisClient

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
        val tx = TransactionManager(dataSources.pg)
        val pepper = "this-is-a-test-pepper-32-bytes-min-length".toByteArray(Charsets.UTF_8)
        userService = UserService(UserRepository(), tx, PasswordHasher(pepper))
        service = OrderService(
            tx = tx,
            instruments = InstrumentRepository(),
            orders = OrderRepository(),
            accounts = AccountRepository(),
            positions = PositionRepository(),
            transactions = TransactionRepository(),
            quotes = QuotesPort(redisModule),
        )
        redisClient = RedisClient.create(RedisURI.create(redisUrl))
    }

    @AfterAll
    fun tearDown() {
        runCatching { redisClient.shutdown() }
        runCatching { redisModule.close() }
        runCatching { dataSources.close() }
        pg.stop()
        redis.stop()
    }

    private fun seedPrice(ticker: String, ask: Long, bid: Long = ask - 50) {
        redisClient.connect().use { conn ->
            conn.sync().hset("quotes:$ticker", mapOf("bid" to bid.toString(), "ask" to ask.toString()))
        }
    }

    @Test
    fun `concurrent BUY at-budget yields one EXECUTED and rest REJECTED`() {
        // Цена такая, что баланс хватает РОВНО на один ордер.
        // initial deposit = 100_000_000; ask = 100_000_000, qty = 1 → cost = 100_000_000.
        seedPrice("SBER", ask = 100_000_000)
        val userId = runBlocking { userService.register("race-budget@example.com", "strong-pass-1") }

        val parallel = 8
        val latch = CountDownLatch(1)
        val executed = AtomicInteger(0)
        val rejected = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(parallel)

        val tasks = (1..parallel).map { i ->
            executor.submit {
                latch.await()
                runBlocking {
                    runCatching {
                        service.place(userId, "SBER", OrderSide.BUY, 1, "race-budget-$i")
                    }.fold(
                        onSuccess = { executed.incrementAndGet() },
                        onFailure = { e -> if (e is InsufficientFundsException) rejected.incrementAndGet() },
                    )
                }
            }
        }
        latch.countDown()
        tasks.forEach { it.get(15, TimeUnit.SECONDS) }
        executor.shutdown()

        executed.get() shouldBe 1
        rejected.get() shouldBe (parallel - 1)

        // Инвариант: баланс не ушёл в минус.
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { conn ->
            val balance = conn.prepareStatement(
                "SELECT balance_cents FROM accounts WHERE user_id = ?",
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
            }
            (balance >= 0) shouldBe true
            balance shouldBe 0L  // EXECUTED списал всё, остальные REJECTED.

            val executedCount = conn.prepareStatement(
                "SELECT count(*) FROM orders WHERE user_id = ? AND status = 'EXECUTED'",
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
            }
            executedCount shouldBe 1
        }
    }

    @Test
    fun `concurrent posts with same idempotency key produce one order`() {
        seedPrice("GAZP", ask = 1000)
        val userId = runBlocking { userService.register("race-idem@example.com", "strong-pass-1") }
        val key = "race-idem-K"

        val parallel = 10
        val latch = CountDownLatch(1)
        val orderIds = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val executor = Executors.newFixedThreadPool(parallel)

        val tasks = (1..parallel).map {
            executor.submit {
                latch.await()
                runBlocking {
                    runCatching {
                        service.place(userId, "GAZP", OrderSide.BUY, 1, key)
                    }.fold(
                        onSuccess = { orderIds += it.id },
                        onFailure = { errors += it },
                    )
                }
            }
        }
        latch.countDown()
        tasks.forEach { it.get(15, TimeUnit.SECONDS) }
        executor.shutdown()

        // Все успешные респонсы должны вернуть один и тот же orderId.
        orderIds.distinct().size shouldBe 1
        // Все ошибки — IdempotencyConflictException не должно быть (тело одинаковое).
        errors.forEach { (it !is IdempotencyConflictException) shouldBe true }

        // В БД ровно один ордер с этим ключом.
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { conn ->
            val count = conn.prepareStatement(
                "SELECT count(*) FROM orders WHERE user_id = ? AND idempotency_key = ?",
            ).use { ps ->
                ps.setString(1, userId)
                ps.setString(2, key)
                ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
            }
            count shouldBe 1
        }
    }
}
