package com.stockyard.gateway.routing

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.stockyard.gateway.config.RedisConfig
import com.stockyard.gateway.redis.RedisModule
import com.stockyard.gateway.test.RedisFixture
import com.stockyard.gateway.test.installTestModule
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install as serverInstall
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.ServerSocket
import java.time.Instant

/**
 * End-to-end IT для `/v1/orders` через embedded mock-core + Testcontainers Redis.
 *
 * mock-core отвечает в зависимости от ticker и качества Authorization:
 *  - "DUP"   → 409 IDEMPOTENCY_CONFLICT
 *  - "BROKE" → 422 INSUFFICIENT_FUNDS (с details)
 *  - "NOPOS" → 422 INSUFFICIENT_POSITION
 *  - "BADTK" → 422 INVALID_TICKER
 *  - "NOPRC" → 422 NO_QUOTE_AVAILABLE
 *  - "SBER"  → 201 normal
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrdersRoutesIT {

    private val redis: GenericContainer<*> = RedisFixture.container()
    private val redisUrl get() = "redis://${redis.host}:${redis.firstMappedPort}"

    private val jwtSecret = "this-is-a-test-secret-32-bytes-min-length"
    private val jwtIssuer = "stockyard-gateway"
    private val jwtAudience = "stockyard-clients"

    private lateinit var mockCore: NettyApplicationEngine
    private var mockCorePort: Int = 0
    private lateinit var redisModule: RedisModule

    @BeforeAll
    fun setUp() {
        redis.start()
        mockCorePort = ServerSocket(0).use { it.localPort }
        mockCore = embeddedServer(Netty, port = mockCorePort) {
            mockCoreOrdersModule()
        }.start(wait = false)
        redisModule = RedisModule(RedisConfig(url = redisUrl, password = ""))
    }

    @AfterAll
    fun tearDown() {
        runCatching { redisModule.close() }
        runCatching { mockCore.stop(100, 200) }
        redis.stop()
    }

    private fun install(builder: io.ktor.server.testing.ApplicationTestBuilder) {
        builder.installTestModule(
            redisUrl = redisUrl,
            coreServiceBaseUrl = "http://127.0.0.1:$mockCorePort",
        )
    }

    private fun client(builder: io.ktor.server.testing.ApplicationTestBuilder) = builder.createClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    /** Создаёт валидный access JWT и SET'ит соответствующий `session:{jti}` в Redis. */
    private fun mintAccessTokenAndStoreSession(userId: String): String {
        val jti = "test-jti-${System.nanoTime()}"
        val alg = Algorithm.HMAC256(jwtSecret)
        val token = JWT.create()
            .withIssuer(jwtIssuer)
            .withAudience(jwtAudience)
            .withSubject(userId)
            .withJWTId(jti)
            .withIssuedAt(Instant.now())
            .withExpiresAt(Instant.now().plusSeconds(900))
            .sign(alg)
        redisModule.withCommandConnection { it.sync().setex("session:$jti", 900, userId) }
        return token
    }

    // --------- happy ----------

    @Test
    fun `POST orders happy path forwards to core and returns 201`() = testApplication {
        install(this)
        val client = client(this)
        val token = mintAccessTokenAndStoreSession("u_test")

        val resp = client.post("/v1/orders") {
            header("Authorization", "Bearer $token")
            header("Idempotency-Key", "K-happy-1")
            contentType(ContentType.Application.Json)
            setBody("""{"ticker":"SBER","side":"BUY","qty":10}""")
        }
        resp.status shouldBe HttpStatusCode.Created
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        body["status"]?.jsonPrimitive?.content shouldBe "EXECUTED"
        body["ticker"]?.jsonPrimitive?.content shouldBe "SBER"
        body["priceCents"]?.jsonPrimitive?.content shouldBe "28570"
    }

    // --------- auth/header validation ----------

    @Test
    fun `POST orders without Authorization returns 401`() = testApplication {
        install(this)
        val resp = client(this).post("/v1/orders") {
            header("Idempotency-Key", "K-1")
            contentType(ContentType.Application.Json)
            setBody("""{"ticker":"SBER","side":"BUY","qty":1}""")
        }
        resp.status shouldBe HttpStatusCode.Unauthorized
    }

    @Test
    fun `POST orders without Idempotency-Key returns 400`() = testApplication {
        install(this)
        val token = mintAccessTokenAndStoreSession("u_no_idem")
        val resp = client(this).post("/v1/orders") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"ticker":"SBER","side":"BUY","qty":1}""")
        }
        resp.status shouldBe HttpStatusCode.BadRequest
        resp.bodyAsText() shouldContain "Idempotency-Key"
    }

    // --------- business errors from core ----------

    @Test
    fun `POST orders propagates INSUFFICIENT_FUNDS with details`() = testApplication {
        install(this)
        val token = mintAccessTokenAndStoreSession("u_broke")
        val resp = client(this).post("/v1/orders") {
            header("Authorization", "Bearer $token")
            header("Idempotency-Key", "K-broke")
            contentType(ContentType.Application.Json)
            setBody("""{"ticker":"BROKE","side":"BUY","qty":10}""")
        }
        resp.status shouldBe HttpStatusCode.UnprocessableEntity
        val body = resp.bodyAsText()
        body shouldContain "\"code\":\"INSUFFICIENT_FUNDS\""
        body shouldContain "\"requiredCents\":125000000"
        body shouldContain "\"availableCents\":100000000"
    }

    @Test
    fun `POST orders propagates INSUFFICIENT_POSITION`() = testApplication {
        install(this)
        val token = mintAccessTokenAndStoreSession("u_nopos")
        val resp = client(this).post("/v1/orders") {
            header("Authorization", "Bearer $token")
            header("Idempotency-Key", "K-nopos")
            contentType(ContentType.Application.Json)
            setBody("""{"ticker":"NOPOS","side":"SELL","qty":5}""")
        }
        resp.status shouldBe HttpStatusCode.UnprocessableEntity
        resp.bodyAsText() shouldContain "\"code\":\"INSUFFICIENT_POSITION\""
    }

    @Test
    fun `POST orders propagates IDEMPOTENCY_CONFLICT`() = testApplication {
        install(this)
        val token = mintAccessTokenAndStoreSession("u_dup")
        val resp = client(this).post("/v1/orders") {
            header("Authorization", "Bearer $token")
            header("Idempotency-Key", "K-dup")
            contentType(ContentType.Application.Json)
            setBody("""{"ticker":"DUP","side":"BUY","qty":1}""")
        }
        resp.status shouldBe HttpStatusCode.Conflict
        resp.bodyAsText() shouldContain "\"code\":\"IDEMPOTENCY_CONFLICT\""
    }

    @Test
    fun `POST orders propagates INVALID_TICKER`() = testApplication {
        install(this)
        val token = mintAccessTokenAndStoreSession("u_badtk")
        val resp = client(this).post("/v1/orders") {
            header("Authorization", "Bearer $token")
            header("Idempotency-Key", "K-badtk")
            contentType(ContentType.Application.Json)
            setBody("""{"ticker":"BADTK","side":"BUY","qty":1}""")
        }
        resp.status shouldBe HttpStatusCode.UnprocessableEntity
        resp.bodyAsText() shouldContain "\"code\":\"INVALID_TICKER\""
    }

    @Test
    fun `POST orders propagates NO_QUOTE_AVAILABLE`() = testApplication {
        install(this)
        val token = mintAccessTokenAndStoreSession("u_noprc")
        val resp = client(this).post("/v1/orders") {
            header("Authorization", "Bearer $token")
            header("Idempotency-Key", "K-noprc")
            contentType(ContentType.Application.Json)
            setBody("""{"ticker":"NOPRC","side":"BUY","qty":1}""")
        }
        resp.status shouldBe HttpStatusCode.UnprocessableEntity
        resp.bodyAsText() shouldContain "\"code\":\"NO_QUOTE_AVAILABLE\""
    }

    // --------- GET listing ----------

    @Test
    fun `GET orders returns paginated list from core`() = testApplication {
        install(this)
        val token = mintAccessTokenAndStoreSession("u_list")
        val resp = client(this).get("/v1/orders?limit=10")
            { header("Authorization", "Bearer $token") }
        resp.status shouldBe HttpStatusCode.OK
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        body["items"].toString() shouldContain "\"orderId\""
        body["nextCursor"]?.jsonPrimitive?.content shouldBe "cursor-next-mock"
    }
}

private fun io.ktor.server.application.Application.mockCoreOrdersModule() {
    serverInstall(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
    routing {
        post("/internal/orders") {
            val text = call.receiveText()
            when {
                text.contains("\"ticker\":\"DUP\"") -> call.respondText(
                    """{"error":{"code":"IDEMPOTENCY_CONFLICT","message":"reused"}}""",
                    ContentType.Application.Json, HttpStatusCode.Conflict,
                )
                text.contains("\"ticker\":\"BROKE\"") -> call.respondText(
                    """{"error":{"code":"INSUFFICIENT_FUNDS","message":"low","details":{"requiredCents":125000000,"availableCents":100000000}}}""",
                    ContentType.Application.Json, HttpStatusCode.UnprocessableEntity,
                )
                text.contains("\"ticker\":\"NOPOS\"") -> call.respondText(
                    """{"error":{"code":"INSUFFICIENT_POSITION","message":"no pos","details":{"requiredQty":5,"availableQty":0}}}""",
                    ContentType.Application.Json, HttpStatusCode.UnprocessableEntity,
                )
                text.contains("\"ticker\":\"BADTK\"") -> call.respondText(
                    """{"error":{"code":"INVALID_TICKER","message":"BADTK"}}""",
                    ContentType.Application.Json, HttpStatusCode.UnprocessableEntity,
                )
                text.contains("\"ticker\":\"NOPRC\"") -> call.respondText(
                    """{"error":{"code":"NO_QUOTE_AVAILABLE","message":"NOPRC"}}""",
                    ContentType.Application.Json, HttpStatusCode.UnprocessableEntity,
                )
                else -> {
                    val oid = "o_mock_${System.nanoTime()}"
                    call.respondText(
                        """{"orderId":"$oid","status":"EXECUTED","ticker":"SBER","side":"BUY","qty":10,"priceCents":28570,"createdAt":"2026-05-11T12:00:00Z","executedAt":"2026-05-11T12:00:00Z"}""",
                        ContentType.Application.Json, HttpStatusCode.Created,
                    )
                }
            }
        }
        get("/internal/users/{userId}/orders") {
            call.respondText(
                """{"items":[{"orderId":"o_1","status":"EXECUTED","ticker":"SBER","side":"BUY","qty":10,"priceCents":28570,"createdAt":"2026-05-11T12:00:00Z","executedAt":"2026-05-11T12:00:00Z"}],"nextCursor":"cursor-next-mock"}""",
                ContentType.Application.Json, HttpStatusCode.OK,
            )
        }
    }
}
