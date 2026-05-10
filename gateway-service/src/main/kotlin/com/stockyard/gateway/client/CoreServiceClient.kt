package com.stockyard.gateway.client

import com.stockyard.gateway.config.CoreServiceConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * HTTP-клиент к Core Service.
 * - TASK-003: только [healthReady] для /health/ready.
 * - TASK-005: [createUser], [authenticate] для auth-flow.
 */
class CoreServiceClient(private val cfg: CoreServiceConfig) : AutoCloseable {

    private val jsonCfg = Json { ignoreUnknownKeys = true }

    private val http: HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(jsonCfg)
        }
        install(HttpTimeout) {
            connectTimeoutMillis = cfg.connectTimeoutMs
            requestTimeoutMillis = cfg.requestTimeoutMs
            socketTimeoutMillis = cfg.requestTimeoutMs
        }
        expectSuccess = false
    }

    suspend fun healthReady(): Boolean = runCatching {
        val resp: HttpResponse = http.get("${cfg.baseUrl}/health/ready")
        resp.status.value in 200..299
    }.getOrElse { false }

    /**
     * POST /internal/users — создание пользователя в core.
     * @return [CreateUserResult.Created] с userId, либо [CreateUserResult.EmailTaken] / [CreateUserResult.Validation].
     * Любой иной HTTP-код — [CoreServiceException] (мапится в 503 на уровне ErrorMapper).
     */
    suspend fun createUser(email: String, password: String): CreateUserResult {
        val resp = http.post("${cfg.baseUrl}/internal/users") {
            contentType(ContentType.Application.Json)
            setBody(InternalCreateUserRequest(email = email, password = password))
        }
        return when (resp.status.value) {
            201 -> CreateUserResult.Created(resp.body<InternalCreateUserResponse>().userId)
            409 -> CreateUserResult.EmailTaken
            422 -> {
                val err = runCatching { resp.body<InternalApiErrorEnvelope>().error }.getOrNull()
                CreateUserResult.Validation(err?.code ?: "INVALID_REQUEST", err?.message ?: "validation failed")
            }
            else -> throw CoreServiceException("createUser failed: HTTP ${resp.status.value}")
        }
    }

    /**
     * POST /internal/auth — проверка credentials в core.
     * Возвращает userId при успехе, null при invalid credentials.
     */
    suspend fun authenticate(email: String, password: String): String? {
        val resp = http.post("${cfg.baseUrl}/internal/auth") {
            contentType(ContentType.Application.Json)
            setBody(InternalAuthRequest(email = email, password = password))
        }
        if (resp.status.value != 200) {
            throw CoreServiceException("authenticate failed: HTTP ${resp.status.value}")
        }
        val body = resp.body<InternalAuthResponse>()
        return if (body.passwordValid) body.userId else null
    }

    override fun close() = http.close()
}

/** Результат createUser — sealed type вместо exceptions для бизнес-исходов. */
sealed interface CreateUserResult {
    data class Created(val userId: String) : CreateUserResult
    data object EmailTaken : CreateUserResult
    data class Validation(val code: String, val message: String) : CreateUserResult
}

/** Бросается при неожиданном HTTP-статусе от core (5xx, network failure). */
class CoreServiceException(message: String) : RuntimeException(message)

@Serializable
private data class InternalCreateUserRequest(val email: String, val password: String)

@Serializable
private data class InternalCreateUserResponse(val userId: String)

@Serializable
private data class InternalAuthRequest(val email: String, val password: String)

@Serializable
private data class InternalAuthResponse(val userId: String? = null, val passwordValid: Boolean)

@Serializable
private data class InternalApiErrorEnvelope(val error: InternalApiError)

@Serializable
private data class InternalApiError(val code: String, val message: String)
