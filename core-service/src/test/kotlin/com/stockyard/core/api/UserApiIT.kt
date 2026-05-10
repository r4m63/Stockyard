package com.stockyard.core.api

import com.stockyard.core.test.PgFixture
import com.stockyard.core.test.RedisFixture
import com.stockyard.core.test.installTestModule
import io.kotest.matchers.collections.shouldHaveSize
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
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
 * IT для `POST /internal/users` и `POST /internal/auth`.
 *
 * Поднимает реальный PostgreSQL через Testcontainers (V1-V7 миграция применяется
 * Flyway-bootstrap при старте Application) + Testcontainers Redis (нужен модулю
 * для readiness, не используется в auth-flow напрямую).
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserApiIT {

    private val pg: PostgreSQLContainer<*> = PgFixture.container()
    private val redis: GenericContainer<*> = RedisFixture.container()
    private val redisUrl get() = "redis://${redis.host}:${redis.firstMappedPort}"

    @BeforeAll fun start() { pg.start(); redis.start() }
    @AfterAll  fun stop()  { pg.stop(); redis.stop() }

    private fun installModule(builder: io.ktor.server.testing.ApplicationTestBuilder) {
        builder.installTestModule(
            pgHost = pg.host, pgPort = pg.firstMappedPort,
            pgUser = pg.username, pgPassword = pg.password,
            redisUrl = redisUrl,
        )
    }

    private fun configuredClient(builder: io.ktor.server.testing.ApplicationTestBuilder) =
        builder.createClient {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

    @Test
    fun `POST internal users — happy path creates user and RUB account`() = testApplication {
        installModule(this)
        val client = configuredClient(this)

        val resp = client.post("/internal/users") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alice@example.com","password":"strong-pass-1"}""")
        }
        resp.status shouldBe HttpStatusCode.Created
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val userId = body["userId"]?.jsonPrimitive?.content ?: error("userId missing")
        userId shouldStartWith "u_"

        // Проверяем DB-state напрямую — независимо от API.
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { conn ->
            conn.prepareStatement("SELECT email, password_hash FROM users WHERE id = ?").use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    rs.getString("email") shouldBe "alice@example.com"
                    rs.getString("password_hash") shouldStartWith "\$argon2id\$"
                }
            }
            conn.prepareStatement(
                "SELECT balance_cents, currency FROM accounts WHERE user_id = ?"
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    rs.getLong("balance_cents") shouldBe 100_000_000L
                    rs.getString("currency") shouldBe "RUB"
                }
            }
        }
    }

    @Test
    fun `POST internal users — duplicate email returns 409 EMAIL_TAKEN`() = testApplication {
        installModule(this)
        val client = configuredClient(this)

        val first = client.post("/internal/users") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"dup@example.com","password":"strong-pass-1"}""")
        }
        first.status shouldBe HttpStatusCode.Created

        val second = client.post("/internal/users") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"dup@example.com","password":"another-pass-1"}""")
        }
        second.status shouldBe HttpStatusCode.Conflict
        second.bodyAsText() shouldContain "\"code\":\"EMAIL_TAKEN\""
    }

    @Test
    fun `POST internal users — invalid email returns 422 INVALID_EMAIL`() = testApplication {
        installModule(this)
        val client = configuredClient(this)
        val resp = client.post("/internal/users") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"not-an-email","password":"strong-pass-1"}""")
        }
        resp.status shouldBe HttpStatusCode.UnprocessableEntity
        resp.bodyAsText() shouldContain "\"code\":\"INVALID_EMAIL\""
    }

    @Test
    fun `POST internal users — short password returns 422 PASSWORD_TOO_WEAK`() = testApplication {
        installModule(this)
        val client = configuredClient(this)
        val resp = client.post("/internal/users") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"weak@example.com","password":"short"}""")
        }
        resp.status shouldBe HttpStatusCode.UnprocessableEntity
        resp.bodyAsText() shouldContain "\"code\":\"PASSWORD_TOO_WEAK\""
    }

    @Test
    fun `POST internal auth — correct password returns passwordValid true`() = testApplication {
        installModule(this)
        val client = configuredClient(this)

        val register = client.post("/internal/users") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"bob@example.com","password":"correct-horse-battery"}""")
        }
        register.status shouldBe HttpStatusCode.Created
        val userId = Json.parseToJsonElement(register.bodyAsText())
            .jsonObject["userId"]!!.jsonPrimitive.content

        val auth = client.post("/internal/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"bob@example.com","password":"correct-horse-battery"}""")
        }
        auth.status shouldBe HttpStatusCode.OK
        val body = Json.parseToJsonElement(auth.bodyAsText()).jsonObject
        body["passwordValid"]?.jsonPrimitive?.content shouldBe "true"
        body["userId"]?.jsonPrimitive?.content shouldBe userId
    }

    @Test
    fun `POST internal auth — wrong password returns passwordValid false`() = testApplication {
        installModule(this)
        val client = configuredClient(this)

        client.post("/internal/users") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"charlie@example.com","password":"correct-pass-1"}""")
        }.status shouldBe HttpStatusCode.Created

        val auth = client.post("/internal/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"charlie@example.com","password":"wrong-pass-X"}""")
        }
        auth.status shouldBe HttpStatusCode.OK
        val body: JsonObject = Json.parseToJsonElement(auth.bodyAsText()).jsonObject
        body["passwordValid"]?.jsonPrimitive?.content shouldBe "false"
        // userId сериализуется как JsonNull → toString() == "null".
        body["userId"].toString() shouldBe "null"
    }

    @Test
    fun `POST internal auth — unknown email returns passwordValid false and userId null`() = testApplication {
        installModule(this)
        val client = configuredClient(this)
        val resp = client.post("/internal/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"ghost@example.com","password":"anything-1234"}""")
        }
        resp.status shouldBe HttpStatusCode.OK
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        body["passwordValid"]?.jsonPrimitive?.content shouldBe "false"
        body["userId"].toString() shouldBe "null"
    }

    @Test
    fun `POST internal users — email is normalized to lowercase`() = testApplication {
        installModule(this)
        val client = configuredClient(this)
        val resp = client.post("/internal/users") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"  MixedCase@Example.COM ","password":"strong-pass-1"}""")
        }
        resp.status shouldBe HttpStatusCode.Created
        val userId = Json.parseToJsonElement(resp.bodyAsText())
            .jsonObject["userId"]!!.jsonPrimitive.content

        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { conn ->
            conn.prepareStatement("SELECT email FROM users WHERE id = ?").use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    rs.getString("email") shouldBe "mixedcase@example.com"
                }
            }
        }

        // authenticate с тем же mixed-case должен сработать (нормализация симметричная).
        val auth = client.post("/internal/auth") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"MIXEDCASE@example.com","password":"strong-pass-1"}""")
        }
        auth.status shouldBe HttpStatusCode.OK
        Json.parseToJsonElement(auth.bodyAsText())
            .jsonObject["passwordValid"]?.jsonPrimitive?.content shouldBe "true"
    }

    @Test
    fun `register and account row counts match — single TX per user`() = testApplication {
        installModule(this)
        val client = configuredClient(this)
        listOf("u1@example.com", "u2@example.com", "u3@example.com").forEach { email ->
            client.post("/internal/users") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"$email","password":"strong-pass-1"}""")
            }.status shouldBe HttpStatusCode.Created
        }

        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { conn ->
            conn.prepareStatement(
                """SELECT u.id, COUNT(a.id) AS acc_count
                   FROM users u LEFT JOIN accounts a ON a.user_id = u.id
                   WHERE u.email LIKE 'u%@example.com'
                   GROUP BY u.id"""
            ).executeQuery().use { rs ->
                val rows = mutableListOf<Int>()
                while (rs.next()) rows += rs.getInt("acc_count")
                rows shouldHaveSize 3
                rows.all { it == 1 } shouldBe true
            }
        }
    }
}

