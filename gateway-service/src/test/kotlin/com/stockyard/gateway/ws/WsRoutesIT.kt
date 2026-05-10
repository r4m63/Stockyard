package com.stockyard.gateway.ws

import com.stockyard.gateway.test.RedisFixture
import com.stockyard.gateway.test.installTestModule
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.consumeEach
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WsRoutesIT {

    private val redis: GenericContainer<*> = RedisFixture.container()
    private val redisUrl get() = "redis://${redis.host}:${redis.firstMappedPort}"
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeAll fun start() = redis.start()
    @AfterAll  fun stop()  = redis.stop()

    @Test
    fun `subscribe action returns subscribed`() = testApplication {
        installTestModule(redisUrl = redisUrl)
        val client = createClient { install(WebSockets) }

        client.webSocket("/v1/ws") {
            send(Frame.Text("""{"action":"subscribe","tickers":["SBER","GAZP"]}"""))
            val response = (incoming.receive() as Frame.Text).readText()
            val obj = json.parseToJsonElement(response) as JsonObject

            obj["type"]!!.jsonPrimitive.content shouldBe "subscribed"
            val tickers = obj["tickers"]!!.jsonArray.map { it.jsonPrimitive.content }
            tickers shouldBe listOf("SBER", "GAZP")
        }
    }

    @Test
    fun `unsubscribe action returns unsubscribed`() = testApplication {
        installTestModule(redisUrl = redisUrl)
        val client = createClient { install(WebSockets) }

        client.webSocket("/v1/ws") {
            send(Frame.Text("""{"action":"subscribe","tickers":["SBER"]}"""))
            incoming.receive()                                                  // skip subscribed

            send(Frame.Text("""{"action":"unsubscribe","tickers":["SBER"]}"""))
            val response = (incoming.receive() as Frame.Text).readText()
            val obj = json.parseToJsonElement(response) as JsonObject

            obj["type"]!!.jsonPrimitive.content shouldBe "unsubscribed"
        }
    }

    @Test
    fun `ping returns pong`() = testApplication {
        installTestModule(redisUrl = redisUrl)
        val client = createClient { install(WebSockets) }

        client.webSocket("/v1/ws") {
            send(Frame.Text("""{"action":"ping"}"""))
            val response = (incoming.receive() as Frame.Text).readText()
            val obj = json.parseToJsonElement(response) as JsonObject

            obj["type"]!!.jsonPrimitive.content shouldBe "pong"
        }
    }

    @Test
    fun `unknown action returns error without echoing user input`() = testApplication {
        installTestModule(redisUrl = redisUrl)
        val client = createClient { install(WebSockets) }

        client.webSocket("/v1/ws") {
            send(Frame.Text("""{"action":"hack-attempt-<script>"}"""))
            val response = (incoming.receive() as Frame.Text).readText()

            response shouldContain "\"type\":\"error\""
            response shouldContain "\"code\":\"UNKNOWN_ACTION\""
            // L3-fix: пользовательский ввод НЕ должен попасть в response.
            (response.contains("hack-attempt") || response.contains("<script>")) shouldBe false
        }
    }

    @Test
    fun `invalid JSON returns INVALID_FRAME error`() = testApplication {
        installTestModule(redisUrl = redisUrl)
        val client = createClient { install(WebSockets) }

        client.webSocket("/v1/ws") {
            send(Frame.Text("this is not json"))
            val response = (incoming.receive() as Frame.Text).readText()

            response shouldContain "\"code\":\"INVALID_FRAME\""
        }
    }

    @Test
    fun `non-text frames are ignored, connection stays alive`() = testApplication {
        installTestModule(redisUrl = redisUrl)
        val client = createClient { install(WebSockets) }

        client.webSocket("/v1/ws") {
            send(Frame.Binary(true, byteArrayOf(1, 2, 3)))
            // После binary — отправляем ping, должны получить pong: соединение не падает.
            send(Frame.Text("""{"action":"ping"}"""))
            val response = (incoming.receive() as Frame.Text).readText()
            response shouldContain "\"type\":\"pong\""
        }
    }
}
