package com.stockyard.gateway.routing

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.stockyard.gateway.config.RedisConfig
import com.stockyard.gateway.redis.RedisModule
import com.stockyard.gateway.test.RedisFixture
import com.stockyard.gateway.test.installTestModule
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install as serverInstall
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end IT для /v1/auth/{register,login,refresh}.
 *
 * Поднимает:
 *  - Testcontainers Redis (реальный, для SessionStore).
 *  - Embedded mock-core Ktor server, отвечающий на /internal/users + /internal/auth
 *    заранее заготовленными ответами по разным email/password.
 *
 * Gateway получает `coreServiceBaseUrl` мока через `installTestModule`.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthRoutesIT {

    private val redis: GenericContainer<*> = RedisFixture.container()
    private val redisUrl get() = "redis://${redis.host}:${redis.firstMappedPort}"

    private lateinit var mockCore: NettyApplicationEngine
    private var mockCorePort: Int = 0
    private val userIdCounter = AtomicInteger(1000)

    private lateinit var redisModule: RedisModule

    @BeforeAll
    fun setUp() {
        redis.start()
        mockCorePort = ServerSocket(0).use { it.localPort }
        mockCore = embeddedServer(Netty, port = mockCorePort) {
            mockCoreModule(userIdCounter)
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

    private fun configuredClient(builder: io.ktor.server.testing.ApplicationTestBuilder) =
        builder.createClient {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

    // ----- /v1/auth/register -----

    @Test
    fun `register happy path returns 201 and stores sessions in Redis`() = testApplication {
        install(this)
        val client = configuredClient(this)

        val resp = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"new-user@example.com","password":"strong-pass-1"}""")
        }
        resp.status shouldBe HttpStatusCode.Created
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        body["userId"]?.jsonPrimitive?.content shouldStartWith "u_mock_"
        body["accessToken"]?.jsonPrimitive?.content!!.isNotBlank() shouldBe true
        body["refreshToken"]?.jsonPrimitive?.content!!.isNotBlank() shouldBe true
        body["expiresIn"]?.jsonPrimitive?.content shouldBe "900"

        // Проверяем что обе сессии присутствуют в Redis по jti токенов.
        val accessJti = JWT.decode(body["accessToken"]!!.jsonPrimitive.content).id
        val refreshJti = JWT.decode(body["refreshToken"]!!.jsonPrimitive.content).id
        redisModule.withCommandConnection { it.sync().exists("session:$accessJti") } shouldBe 1L
        redisModule.withCommandConnection { it.sync().exists("refresh:$refreshJti") } shouldBe 1L
    }

    @Test
    fun `register with duplicate email returns 409 EMAIL_TAKEN`() = testApplication {
        install(this)
        val client = configuredClient(this)
        val resp = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"dup@example.com","password":"strong-pass-1"}""")
        }
        resp.status shouldBe HttpStatusCode.Conflict
        resp.bodyAsText() shouldContain "\"code\":\"EMAIL_TAKEN\""
    }

    @Test
    fun `register with invalid email returns 422 INVALID_EMAIL — gateway DTO validation`() = testApplication {
        install(this)
        val client = configuredClient(this)
        val resp = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"not-an-email","password":"strong-pass-1"}""")
        }
        resp.status shouldBe HttpStatusCode.UnprocessableEntity
        resp.bodyAsText() shouldContain "\"code\":\"INVALID_EMAIL\""
    }

    @Test
    fun `register with short password returns 422 PASSWORD_TOO_WEAK — gateway DTO validation`() = testApplication {
        install(this)
        val client = configuredClient(this)
        val resp = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"user@example.com","password":"short"}""")
        }
        resp.status shouldBe HttpStatusCode.UnprocessableEntity
        resp.bodyAsText() shouldContain "\"code\":\"PASSWORD_TOO_WEAK\""
    }

    // ----- /v1/auth/login -----

    @Test
    fun `login with correct credentials returns 200 and tokens`() = testApplication {
        install(this)
        val client = configuredClient(this)
        val resp = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"existing@example.com","password":"right-pass"}""")
        }
        resp.status shouldBe HttpStatusCode.OK
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        body["accessToken"]?.jsonPrimitive?.content!!.isNotBlank() shouldBe true
        body["refreshToken"]?.jsonPrimitive?.content!!.isNotBlank() shouldBe true
    }

    @Test
    fun `login with wrong password returns 401 INVALID_CREDENTIALS`() = testApplication {
        install(this)
        val client = configuredClient(this)
        val resp = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"existing@example.com","password":"wrong-pass"}""")
        }
        resp.status shouldBe HttpStatusCode.Unauthorized
        resp.bodyAsText() shouldContain "\"code\":\"INVALID_CREDENTIALS\""
    }

    // ----- /v1/auth/refresh -----

    @Test
    fun `refresh happy path returns new tokens and revokes old refresh`() = testApplication {
        install(this)
        val client = configuredClient(this)

        // Получим валидный refresh через register.
        val registerResp = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"refresh-1@example.com","password":"strong-pass-1"}""")
        }
        val registerBody = Json.parseToJsonElement(registerResp.bodyAsText()).jsonObject
        val oldRefresh = registerBody["refreshToken"]!!.jsonPrimitive.content
        val oldRefreshJti = JWT.decode(oldRefresh).id

        val resp = client.post("/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$oldRefresh"}""")
        }
        resp.status shouldBe HttpStatusCode.OK
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val newAccess = body["accessToken"]!!.jsonPrimitive.content
        val newRefresh = body["refreshToken"]!!.jsonPrimitive.content
        newAccess.isNotBlank() shouldBe true
        newRefresh.isNotBlank() shouldBe true
        (newRefresh != oldRefresh) shouldBe true

        // Старый refresh-jti удалён из Redis.
        redisModule.withCommandConnection { it.sync().exists("refresh:$oldRefreshJti") } shouldBe 0L
        // Новый refresh-jti есть.
        val newJti = JWT.decode(newRefresh).id
        redisModule.withCommandConnection { it.sync().exists("refresh:$newJti") } shouldBe 1L
    }

    @Test
    fun `refresh with foreign signature returns 401 INVALID_REFRESH_TOKEN`() = testApplication {
        install(this)
        val client = configuredClient(this)

        val foreign = Algorithm.HMAC256("completely-different-secret-of-32-bytes-here")
        val foreignToken = JWT.create()
            .withIssuer("stockyard-gateway")
            .withAudience("stockyard-clients")
            .withSubject("u_attacker")
            .withJWTId("attacker-jti")
            .withExpiresAt(Instant.now().plusSeconds(60))
            .sign(foreign)

        val resp = client.post("/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$foreignToken"}""")
        }
        resp.status shouldBe HttpStatusCode.Unauthorized
        resp.bodyAsText() shouldContain "\"code\":\"INVALID_REFRESH_TOKEN\""
    }

    @Test
    fun `refresh reuse of rotated token returns 401`() = testApplication {
        install(this)
        val client = configuredClient(this)

        val registerResp = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"reuse@example.com","password":"strong-pass-1"}""")
        }
        val oldRefresh = Json.parseToJsonElement(registerResp.bodyAsText())
            .jsonObject["refreshToken"]!!.jsonPrimitive.content

        // Первый refresh — успешный.
        client.post("/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$oldRefresh"}""")
        }.status shouldBe HttpStatusCode.OK

        // Второй раз тот же refresh — должен быть отвергнут (jti уже не в Redis).
        val replay = client.post("/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$oldRefresh"}""")
        }
        replay.status shouldBe HttpStatusCode.Unauthorized
        replay.bodyAsText() shouldContain "\"code\":\"INVALID_REFRESH_TOKEN\""
    }
}

/**
 * Embedded mock-core Ktor module. Делает то, что в проде делает core-service:
 *  - `POST /internal/users` — 201 для большинства email, 409 для "dup@example.com",
 *    422 для отсутствия валидации (тут не проверяем — gateway отсек заранее).
 *  - `POST /internal/auth` — 200 c passwordValid=true для пароля "right-pass",
 *    иначе passwordValid=false.
 */
private fun io.ktor.server.application.Application.mockCoreModule(counter: AtomicInteger) {
    serverInstall(ServerContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
    }
    routing {
        post("/internal/users") {
            val text = call.receiveText()
            when {
                text.contains("\"dup@example.com\"") ->
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to mapOf("code" to "EMAIL_TAKEN", "message" to "email already registered")))
                else -> {
                    val uid = "u_mock_${counter.getAndIncrement()}"
                    call.respondText("""{"userId":"$uid"}""", ContentType.Application.Json, HttpStatusCode.Created)
                }
            }
        }
        post("/internal/auth") {
            val text = call.receiveText()
            val isRightPass = text.contains("\"password\":\"right-pass\"")
            if (isRightPass) {
                call.respondText(
                    """{"userId":"u_mock_existing","passwordValid":true}""",
                    ContentType.Application.Json,
                    HttpStatusCode.OK,
                )
            } else {
                call.respondText(
                    """{"userId":null,"passwordValid":false}""",
                    ContentType.Application.Json,
                    HttpStatusCode.OK,
                )
            }
        }
    }
}
