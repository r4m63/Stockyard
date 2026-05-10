package com.stockyard.gateway.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.stockyard.gateway.client.CoreServiceClient
import com.stockyard.gateway.client.CreateUserResult
import com.stockyard.gateway.config.JwtConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Unit-тесты для [AuthService] с моками [CoreServiceClient] и [SessionStore].
 * [JwtVerifiers] — реальный (легковесный, без зависимостей).
 */
class AuthServiceTest {

    private val jwtConfig = JwtConfig(
        secret = "this-is-a-test-secret-32-bytes-min-length",
        issuer = "stockyard-gateway",
        audience = "stockyard-clients",
        accessTtlSeconds = 900,
        refreshTtlSeconds = 2_592_000,
    )
    private val jwtVerifiers = JwtVerifiers(jwtConfig)

    private fun newService(
        coreClient: CoreServiceClient = mockk(),
        sessions: SessionStore = mockk(relaxed = true),
    ) = AuthService(coreClient, jwtVerifiers, sessions, jwtConfig)

    // ----- register -----

    @Test
    fun `register — empty email throws GatewayValidationException INVALID_EMAIL`() = runTest {
        val service = newService()
        val ex = shouldThrow<GatewayValidationException> { service.register("", "password-1") }
        ex.errorCode shouldBe "INVALID_EMAIL"
    }

    @Test
    fun `register — short password throws PASSWORD_TOO_WEAK`() = runTest {
        val service = newService()
        val ex = shouldThrow<GatewayValidationException> {
            service.register("user@example.com", "short")
        }
        ex.errorCode shouldBe "PASSWORD_TOO_WEAK"
    }

    @Test
    fun `register — happy path returns userId and stores both sessions`() = runTest {
        val coreClient = mockk<CoreServiceClient>()
        val sessions = mockk<SessionStore>(relaxed = true)
        coEvery { coreClient.createUser("user@example.com", "password-1") } returns
            CreateUserResult.Created("u_abc123")
        val service = newService(coreClient, sessions)

        val result = service.register("user@example.com", "password-1")

        result.userId shouldBe "u_abc123"
        result.tokens.accessToken.shouldNotBeEmpty()
        result.tokens.refreshToken.shouldNotBeEmpty()
        result.tokens.expiresIn shouldBe jwtConfig.accessTtlSeconds

        verify(exactly = 1) {
            sessions.storeAccessSession(any(), "u_abc123", jwtConfig.accessTtlSeconds)
        }
        verify(exactly = 1) {
            sessions.storeRefreshSession(any(), "u_abc123", jwtConfig.refreshTtlSeconds)
        }

        // Access-токен корректно подписан и расшифровывается под наш verifier.
        val decoded = jwtVerifiers.accessVerifier.verify(result.tokens.accessToken)
        decoded.subject shouldBe "u_abc123"
    }

    @Test
    fun `register — core returns EmailTaken throws EmailTakenException`() = runTest {
        val coreClient = mockk<CoreServiceClient>()
        coEvery { coreClient.createUser(any(), any()) } returns CreateUserResult.EmailTaken
        val service = newService(coreClient)

        shouldThrow<EmailTakenException> { service.register("dup@example.com", "password-1") }
    }

    @Test
    fun `register — core returns Validation throws GatewayValidationException with code`() = runTest {
        val coreClient = mockk<CoreServiceClient>()
        coEvery { coreClient.createUser(any(), any()) } returns
            CreateUserResult.Validation("INVALID_EMAIL", "core says invalid")
        val service = newService(coreClient)

        val ex = shouldThrow<GatewayValidationException> {
            service.register("user@example.com", "password-1")
        }
        ex.errorCode shouldBe "INVALID_EMAIL"
    }

    // ----- login -----

    @Test
    fun `login — wrong credentials throws InvalidCredentialsException`() = runTest {
        val coreClient = mockk<CoreServiceClient>()
        coEvery { coreClient.authenticate(any(), any()) } returns null
        val service = newService(coreClient)

        shouldThrow<InvalidCredentialsException> {
            service.login("user@example.com", "wrong-pass")
        }
    }

    @Test
    fun `login — correct credentials returns tokens and stores sessions`() = runTest {
        val coreClient = mockk<CoreServiceClient>()
        val sessions = mockk<SessionStore>(relaxed = true)
        coEvery { coreClient.authenticate("user@example.com", "right-pass") } returns "u_abc"
        val service = newService(coreClient, sessions)

        val tokens = service.login("user@example.com", "right-pass")

        tokens.accessToken.shouldNotBeEmpty()
        tokens.refreshToken.shouldNotBeEmpty()
        verify(exactly = 1) { sessions.storeAccessSession(any(), "u_abc", 900) }
        verify(exactly = 1) { sessions.storeRefreshSession(any(), "u_abc", 2_592_000) }
    }

    @Test
    fun `login — validation runs before core call`() = runTest {
        val coreClient = mockk<CoreServiceClient>()
        val service = newService(coreClient)
        shouldThrow<GatewayValidationException> { service.login("bad email", "password-1") }
        // CoreServiceClient НЕ должен быть вызван — даже без any answers.
        coVerify(exactly = 0) { coreClient.authenticate(any(), any()) }
    }

    // ----- refresh -----

    @Test
    fun `refresh — invalid signature throws InvalidRefreshTokenException`() = runTest {
        val service = newService()
        val foreign = Algorithm.HMAC256("completely-different-secret-of-32-bytes-here")
        val foreignToken = JWT.create()
            .withIssuer(jwtConfig.issuer)
            .withAudience(jwtConfig.audience)
            .withSubject("u_attacker")
            .withJWTId("attacker-jti")
            .withExpiresAt(Instant.now().plusSeconds(60))
            .sign(foreign)

        shouldThrow<InvalidRefreshTokenException> { service.refresh(foreignToken) }
    }

    @Test
    fun `refresh — expired token throws InvalidRefreshTokenException`() = runTest {
        val service = newService()
        val expired = JWT.create()
            .withIssuer(jwtConfig.issuer)
            .withAudience(jwtConfig.audience)
            .withSubject("u_test")
            .withJWTId("jti-expired")
            .withIssuedAt(Instant.now().minusSeconds(1000))
            .withExpiresAt(Instant.now().minusSeconds(100))
            .sign(jwtVerifiers.algorithm)

        shouldThrow<InvalidRefreshTokenException> { service.refresh(expired) }
    }

    @Test
    fun `refresh — valid token but jti not in Redis throws InvalidRefreshTokenException`() = runTest {
        val sessions = mockk<SessionStore>()
        every { sessions.refreshSessionExists(any()) } returns false
        val service = newService(sessions = sessions)

        val token = jwtVerifiers.issueRefreshToken("u_test", "jti-revoked")
        shouldThrow<InvalidRefreshTokenException> { service.refresh(token) }
        verify { sessions.refreshSessionExists("jti-revoked") }
    }

    @Test
    fun `refresh — happy path deletes old jti and issues new tokens`() = runTest {
        val sessions = mockk<SessionStore>(relaxed = true)
        every { sessions.refreshSessionExists("jti-old") } returns true
        val service = newService(sessions = sessions)

        val oldToken = jwtVerifiers.issueRefreshToken("u_abc", "jti-old")
        val newTokens = service.refresh(oldToken)

        newTokens.accessToken.shouldNotBeEmpty()
        newTokens.refreshToken.shouldNotBeEmpty()
        verify(exactly = 1) { sessions.deleteRefreshSession("jti-old") }
        verify(exactly = 1) { sessions.storeAccessSession(any(), "u_abc", 900) }
        verify(exactly = 1) { sessions.storeRefreshSession(any(), "u_abc", 2_592_000) }
    }
}
