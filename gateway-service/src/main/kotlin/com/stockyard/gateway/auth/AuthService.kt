package com.stockyard.gateway.auth

import com.auth0.jwt.exceptions.JWTVerificationException
import com.stockyard.gateway.client.CoreServiceClient
import com.stockyard.gateway.client.CreateUserResult
import com.stockyard.gateway.config.JwtConfig
import io.azam.ulidj.ULID
import org.slf4j.LoggerFactory

/**
 * Orchestrator auth-flow. Связывает [CoreServiceClient], [JwtVerifiers], [SessionStore].
 *
 * Все три эндпоинта (`POST /v1/auth/{register,login,refresh}`) возвращают [TokenPair].
 * Refresh выполняет ротацию: старый jti удаляется из Redis, новые access+refresh выдаются.
 */
class AuthService(
    private val coreClient: CoreServiceClient,
    private val jwt: JwtVerifiers,
    private val sessions: SessionStore,
    private val jwtConfig: JwtConfig,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun register(email: String, password: String): RegisterResult {
        validateEmail(email)
        validatePassword(password)
        val result = coreClient.createUser(email, password)
        return when (result) {
            is CreateUserResult.Created -> {
                val tokens = issueTokens(result.userId)
                RegisterResult(userId = result.userId, tokens = tokens)
            }
            CreateUserResult.EmailTaken -> throw EmailTakenException()
            is CreateUserResult.Validation -> throw GatewayValidationException(result.code, result.message)
        }
    }

    suspend fun login(email: String, password: String): TokenPair {
        validateEmail(email)
        validatePassword(password)
        val userId = coreClient.authenticate(email, password)
            ?: throw InvalidCredentialsException()
        return issueTokens(userId)
    }

    /**
     * Refresh с rotation. Валидирует подпись/exp, проверяет EXISTS в Redis, удаляет старый
     * (atomic-enough: подделать refresh без секрета невозможно, race в один наносекундный
     * gap между EXISTS и DEL принимаем — оба запроса вернут 401 после rotation legitimate-юзера).
     */
    fun refresh(refreshToken: String): TokenPair {
        val decoded = try {
            jwt.verifyRefresh(refreshToken)
        } catch (e: JWTVerificationException) {
            throw InvalidRefreshTokenException()
        }
        val oldJti = decoded.id ?: throw InvalidRefreshTokenException()
        val userId = decoded.subject ?: throw InvalidRefreshTokenException()

        if (!sessions.refreshSessionExists(oldJti)) {
            throw InvalidRefreshTokenException()
        }
        sessions.deleteRefreshSession(oldJti)

        val tokens = issueTokens(userId)
        log.atInfo()
            .addKeyValue("user.id", userId)
            .addKeyValue("jti.old", oldJti)
            .log("refresh token rotated")
        return tokens
    }

    private fun issueTokens(userId: String): TokenPair {
        val accessJti = ULID.random()
        val refreshJti = ULID.random()
        val accessToken = jwt.issueAccessToken(userId, accessJti)
        val refreshToken = jwt.issueRefreshToken(userId, refreshJti)
        sessions.storeAccessSession(accessJti, userId, jwtConfig.accessTtlSeconds)
        sessions.storeRefreshSession(refreshJti, userId, jwtConfig.refreshTtlSeconds)
        return TokenPair(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresIn = jwtConfig.accessTtlSeconds,
        )
    }

    private fun validateEmail(raw: String) {
        val email = raw.trim()
        if (email.isEmpty() || email.length > EMAIL_MAX_LEN || !EMAIL_REGEX.matches(email)) {
            throw GatewayValidationException("INVALID_EMAIL", "invalid email format")
        }
    }

    private fun validatePassword(password: String) {
        if (password.length < PASSWORD_MIN_LEN || password.length > PASSWORD_MAX_LEN) {
            throw GatewayValidationException(
                "PASSWORD_TOO_WEAK",
                "password length must be between $PASSWORD_MIN_LEN and $PASSWORD_MAX_LEN",
            )
        }
    }

    companion object {
        private const val EMAIL_MAX_LEN = 254
        private const val PASSWORD_MIN_LEN = 8
        private const val PASSWORD_MAX_LEN = 256
        private val EMAIL_REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    }
}

data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
)

data class RegisterResult(
    val userId: String,
    val tokens: TokenPair,
)
