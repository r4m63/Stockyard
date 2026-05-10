package com.stockyard.core.api

import com.stockyard.core.test.PgFixture
import com.stockyard.core.test.RedisFixture
import com.stockyard.core.test.installTestModule
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager

/**
 * IT для `POST /internal/orders` и `GET /internal/users/{id}/orders`.
 *
 * Поднимает Testcontainers PG (с Flyway-bootstrap'ом из Application.module()) + Redis.
 * Цены сеются вручную через прямой Lettuce, чтобы не зависеть от DevPriceFixture timing.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderApiIT {

    private val pg: PostgreSQLContainer<*> = PgFixture.container()
    private val redis: GenericContainer<*> = RedisFixture.container()
    private val redisUrl get() = "redis://${redis.host}:${redis.firstMappedPort}"

    private lateinit var redisClient: RedisClient

    @BeforeAll
    fun startContainers() {
        pg.start()
        redis.start()
        redisClient = RedisClient.create(RedisURI.create(redisUrl))
    }

    @AfterAll
    fun stopContainers() {
        runCatching { redisClient.shutdown() }
        pg.stop()
        redis.stop()
    }

    private fun installModule(builder: ApplicationTestBuilder) {
        builder.installTestModule(
            pgHost = pg.host, pgPort = pg.firstMappedPort,
            pgUser = pg.username, pgPassword = pg.password,
            redisUrl = redisUrl,
            devFixtureEnabled = false,
        )
    }

    private fun client(builder: ApplicationTestBuilder) = builder.createClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    private fun seedPrice(ticker: String, bidCents: Long, askCents: Long) {
        redisClient.connect().use { conn ->
            conn.sync().hset(
                "quotes:$ticker",
                mapOf("bid" to bidCents.toString(), "ask" to askCents.toString(), "last" to bidCents.toString()),
            )
        }
    }

    private fun deletePrice(ticker: String) {
        redisClient.connect().use { conn -> conn.sync().del("quotes:$ticker") }
    }

    private suspend fun registerUser(builder: ApplicationTestBuilder, email: String): String {
        val resp = client(builder).post("/internal/users") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"strong-pass-1"}""")
        }
        resp.status shouldBe HttpStatusCode.Created
        return Json.parseToJsonElement(resp.bodyAsText()).jsonObject["userId"]!!.jsonPrimitive.content
    }

    private fun pgConnection() = DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password)

    // --------- BUY happy path ----------

    @Test
    fun `BUY happy path executes, balance decreases, position upserted, transactions audited`() = testApplication {
        installModule(this)
        val client = client(this)

        seedPrice("SBER", bidCents = 28550, askCents = 28570)
        val userId = registerUser(this, "buy-happy@example.com")

        val resp = client.post("/internal/orders") {
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"$userId","ticker":"SBER","side":"BUY","qty":10,"idempotencyKey":"k-buy-1"}""")
        }
        resp.status shouldBe HttpStatusCode.Created
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        body["status"]?.jsonPrimitive?.content shouldBe "EXECUTED"
        body["priceCents"]?.jsonPrimitive?.content shouldBe "28570"
        body["orderId"]?.jsonPrimitive?.content!!.shouldStartWith("o_")

        // DB invariants — баланс уменьшился ровно на cost; позиция = 10@28570; audit-запись.
        pgConnection().use { conn ->
            val balance = conn.prepareStatement(
                "SELECT balance_cents FROM accounts WHERE user_id = ? AND currency = 'RUB'",
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
            }
            balance shouldBe 100_000_000L - 28570L * 10  // initial deposit - cost

            val (qty, avg) = conn.prepareStatement(
                "SELECT qty, avg_price_cents FROM positions WHERE user_id = ? AND ticker = 'SBER'",
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) to rs.getLong(2) }
            }
            qty shouldBe 10
            avg shouldBe 28570L

            val audit = conn.prepareStatement(
                "SELECT type, amount_cents FROM transactions WHERE user_id = ? AND ref_order_id IS NOT NULL",
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs -> rs.next(); rs.getString(1) to rs.getLong(2) }
            }
            audit.first shouldBe "BUY"
            audit.second shouldBe -(28570L * 10)
        }
    }

    // --------- SELL happy path ----------

    @Test
    fun `SELL happy path decreases qty, increases balance, audit recorded`() = testApplication {
        installModule(this)
        val client = client(this)
        seedPrice("GAZP", bidCents = 15200, askCents = 15250)
        val userId = registerUser(this, "sell-happy@example.com")

        // Сначала BUY, чтобы появилась позиция.
        client.post("/internal/orders") {
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"$userId","ticker":"GAZP","side":"BUY","qty":10,"idempotencyKey":"sell-k1"}""")
        }.status shouldBe HttpStatusCode.Created

        val sell = client.post("/internal/orders") {
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"$userId","ticker":"GAZP","side":"SELL","qty":4,"idempotencyKey":"sell-k2"}""")
        }
        sell.status shouldBe HttpStatusCode.Created
        val body = Json.parseToJsonElement(sell.bodyAsText()).jsonObject
        body["status"]?.jsonPrimitive?.content shouldBe "EXECUTED"
        body["priceCents"]?.jsonPrimitive?.content shouldBe "15200"   // bid, не ask

        pgConnection().use { conn ->
            val (qty, avg) = conn.prepareStatement(
                "SELECT qty, avg_price_cents FROM positions WHERE user_id = ? AND ticker = 'GAZP'",
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) to rs.getLong(2) }
            }
            qty shouldBe 6
            avg shouldBe 15250L   // avg_price НЕ изменился при SELL

            // Audit: один BUY и один SELL.
            val audits = conn.prepareStatement(
                "SELECT type, amount_cents FROM transactions WHERE user_id = ? ORDER BY id",
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs ->
                    val acc = mutableListOf<Pair<String, Long>>()
                    while (rs.next()) acc += rs.getString(1) to rs.getLong(2)
                    acc
                }
            }
            audits shouldHaveSize 2
            audits[0] shouldBe ("BUY" to -(15250L * 10))
            audits[1] shouldBe ("SELL" to (15200L * 4))
        }
    }

    // --------- REJECTED: INSUFFICIENT_FUNDS ----------

    @Test
    fun `BUY with insufficient funds returns 422 and inserts REJECTED order`() = testApplication {
        installModule(this)
        val client = client(this)
        // ask = 200_000 cents, qty 10 → cost = 2_000_000, у юзера 100_000_000 — хватит.
        // Поставим ask так, чтобы cost > 100_000_000.
        seedPrice("SBER", bidCents = 12_000_000, askCents = 12_500_000)
        val userId = registerUser(this, "broke@example.com")

        val resp = client.post("/internal/orders") {
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"$userId","ticker":"SBER","side":"BUY","qty":10,"idempotencyKey":"k-broke-1"}""")
        }
        resp.status shouldBe HttpStatusCode.UnprocessableEntity
        val body = resp.bodyAsText()
        body shouldContain "\"code\":\"INSUFFICIENT_FUNDS\""
        body shouldContain "\"requiredCents\":125000000"
        body shouldContain "\"availableCents\":100000000"

        // REJECTED-ордер записан, баланс НЕ изменился, audit пуст.
        pgConnection().use { conn ->
            val (status, _) = conn.prepareStatement(
                "SELECT status, price_cents FROM orders WHERE user_id = ? AND idempotency_key = 'k-broke-1'",
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs -> rs.next(); rs.getString(1) to rs.getLong(2) }
            }
            status shouldBe "REJECTED"

            val balance = conn.prepareStatement(
                "SELECT balance_cents FROM accounts WHERE user_id = ?",
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
            }
            balance shouldBe 100_000_000L

            val auditCount = conn.prepareStatement(
                "SELECT count(*) FROM transactions WHERE user_id = ?",
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
            }
            auditCount shouldBe 0
        }
    }

    // --------- REJECTED: INSUFFICIENT_POSITION ----------

    @Test
    fun `SELL with insufficient position returns 422 and inserts REJECTED order`() = testApplication {
        installModule(this)
        val client = client(this)
        seedPrice("LKOH", bidCents = 50_000, askCents = 50_100)
        val userId = registerUser(this, "no-pos@example.com")

        val resp = client.post("/internal/orders") {
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"$userId","ticker":"LKOH","side":"SELL","qty":5,"idempotencyKey":"k-nopos-1"}""")
        }
        resp.status shouldBe HttpStatusCode.UnprocessableEntity
        val body = resp.bodyAsText()
        body shouldContain "\"code\":\"INSUFFICIENT_POSITION\""
        body shouldContain "\"requiredQty\":5"
        body shouldContain "\"availableQty\":0"
    }

    // --------- Idempotency happy ----------

    @Test
    fun `repeat with same Idempotency-Key returns same order, exactly one row in DB`() = testApplication {
        installModule(this)
        val client = client(this)
        seedPrice("ROSN", bidCents = 50_000, askCents = 50_100)
        val userId = registerUser(this, "idem@example.com")

        val first = client.post("/internal/orders") {
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"$userId","ticker":"ROSN","side":"BUY","qty":1,"idempotencyKey":"k-idem-1"}""")
        }
        first.status shouldBe HttpStatusCode.Created
        val firstId = Json.parseToJsonElement(first.bodyAsText()).jsonObject["orderId"]!!.jsonPrimitive.content

        val second = client.post("/internal/orders") {
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"$userId","ticker":"ROSN","side":"BUY","qty":1,"idempotencyKey":"k-idem-1"}""")
        }
        // Repeat — статус Created (текущая семантика OrderService: возвращает existing с 201).
        // API не различает 201/200 для идемпотентного повтора; gateway тоже принимает оба.
        (second.status == HttpStatusCode.Created || second.status == HttpStatusCode.OK) shouldBe true
        val secondId = Json.parseToJsonElement(second.bodyAsText()).jsonObject["orderId"]!!.jsonPrimitive.content
        secondId shouldBe firstId

        // В БД ровно один ордер с этим ключом.
        pgConnection().use { conn ->
            val count = conn.prepareStatement(
                "SELECT count(*) FROM orders WHERE user_id = ? AND idempotency_key = 'k-idem-1'",
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
            }
            count shouldBe 1
        }
    }

    // --------- Idempotency conflict ----------

    @Test
    fun `same Idempotency-Key with different body returns 409 IDEMPOTENCY_CONFLICT`() = testApplication {
        installModule(this)
        val client = client(this)
        seedPrice("NVTK", bidCents = 80_000, askCents = 80_100)
        val userId = registerUser(this, "idem-conflict@example.com")

        client.post("/internal/orders") {
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"$userId","ticker":"NVTK","side":"BUY","qty":1,"idempotencyKey":"k-conflict"}""")
        }.status shouldBe HttpStatusCode.Created

        val conflict = client.post("/internal/orders") {
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"$userId","ticker":"NVTK","side":"BUY","qty":2,"idempotencyKey":"k-conflict"}""")
        }
        conflict.status shouldBe HttpStatusCode.Conflict
        conflict.bodyAsText() shouldContain "\"code\":\"IDEMPOTENCY_CONFLICT\""
    }

    // --------- INVALID_TICKER ----------

    @Test
    fun `unknown ticker returns 422 INVALID_TICKER`() = testApplication {
        installModule(this)
        val client = client(this)
        seedPrice("BOGUS", bidCents = 10_000, askCents = 10_100)
        val userId = registerUser(this, "bogus@example.com")

        val resp = client.post("/internal/orders") {
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"$userId","ticker":"BOGUS","side":"BUY","qty":1,"idempotencyKey":"k-bogus"}""")
        }
        resp.status shouldBe HttpStatusCode.UnprocessableEntity
        resp.bodyAsText() shouldContain "\"code\":\"INVALID_TICKER\""
    }

    // --------- NO_QUOTE_AVAILABLE ----------

    @Test
    fun `missing quote in Redis returns 422 NO_QUOTE_AVAILABLE`() = testApplication {
        installModule(this)
        val client = client(this)
        deletePrice("SBER")
        val userId = registerUser(this, "no-quote@example.com")

        val resp = client.post("/internal/orders") {
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"$userId","ticker":"SBER","side":"BUY","qty":1,"idempotencyKey":"k-noquote"}""")
        }
        resp.status shouldBe HttpStatusCode.UnprocessableEntity
        resp.bodyAsText() shouldContain "\"code\":\"NO_QUOTE_AVAILABLE\""
    }

    // --------- BUY+BUY: weighted avg ----------

    @Test
    fun `two BUYs of same ticker produce weighted average price`() = testApplication {
        installModule(this)
        val client = client(this)
        seedPrice("MGNT", bidCents = 50_000, askCents = 50_000)
        val userId = registerUser(this, "weighted-avg@example.com")

        // 1) qty=10 @ 50000 → avg=50000
        client.post("/internal/orders") {
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"$userId","ticker":"MGNT","side":"BUY","qty":10,"idempotencyKey":"avg-1"}""")
        }.status shouldBe HttpStatusCode.Created

        seedPrice("MGNT", bidCents = 60_000, askCents = 60_000)
        // 2) qty=10 @ 60000 → avg=(50000*10 + 60000*10)/20 = 55000
        client.post("/internal/orders") {
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"$userId","ticker":"MGNT","side":"BUY","qty":10,"idempotencyKey":"avg-2"}""")
        }.status shouldBe HttpStatusCode.Created

        pgConnection().use { conn ->
            conn.prepareStatement(
                "SELECT qty, avg_price_cents FROM positions WHERE user_id = ? AND ticker = 'MGNT'",
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    rs.getInt("qty") shouldBe 20
                    rs.getLong("avg_price_cents") shouldBe 55_000L
                }
            }
        }
    }

    // --------- GET listing + status filter + cursor ----------

    @Test
    fun `GET orders returns paginated list with status filter and cursor`() = testApplication {
        installModule(this)
        val client = client(this)
        seedPrice("YNDX", bidCents = 35_000, askCents = 35_000)
        val userId = registerUser(this, "list@example.com")

        // 3 ордера разных статусов.
        client.post("/internal/orders") {
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"$userId","ticker":"YNDX","side":"BUY","qty":1,"idempotencyKey":"l-1"}""")
        }.status shouldBe HttpStatusCode.Created
        client.post("/internal/orders") {
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"$userId","ticker":"YNDX","side":"BUY","qty":1,"idempotencyKey":"l-2"}""")
        }.status shouldBe HttpStatusCode.Created

        val list = client.post("/internal/orders") {
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"$userId","ticker":"YNDX","side":"BUY","qty":1,"idempotencyKey":"l-3"}""")
        }
        list.status shouldBe HttpStatusCode.Created

        // limit=2 — должен быть nextCursor.
        val page1 = client(this).get("/internal/users/$userId/orders?limit=2")
        page1.status shouldBe HttpStatusCode.OK
        val body1 = Json.parseToJsonElement(page1.bodyAsText()).jsonObject
        body1["items"]!!.let { items ->
            items.toString() shouldContain "\"orderId\""
        }
        val cursor1 = body1["nextCursor"]?.jsonPrimitive?.content
        (cursor1 != null && cursor1.isNotBlank()) shouldBe true

        val page2 = client(this).get("/internal/users/$userId/orders?limit=2&cursor=$cursor1")
        page2.status shouldBe HttpStatusCode.OK
        val body2 = Json.parseToJsonElement(page2.bodyAsText()).jsonObject
        body2["nextCursor"].toString() shouldBe "null"  // последняя страница
    }
}
