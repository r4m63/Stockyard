package com.stockyard.gateway.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.stockyard.gateway.config.JwtConfig
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

class JwtVerifiersTest {

    private val cfg = JwtConfig(
        secret = "this-is-a-test-secret-32-bytes-min-length",
        issuer = "stockyard-gateway",
        audience = "stockyard-clients",
        accessTtlSeconds = 900,
        refreshTtlSeconds = 2592000,
    )
    private val verifiers = JwtVerifiers(cfg)

    @Test
    fun `issued access token is verifiable`() {
        val token = verifiers.issueAccessToken("u_test01", "jti-1")

        shouldNotThrow<JWTVerificationException> {
            val decoded = verifiers.accessVerifier.verify(token)
            decoded.subject shouldBe "u_test01"
            decoded.id shouldBe "jti-1"
            decoded.issuer shouldBe cfg.issuer
            decoded.audience shouldBe listOf(cfg.audience)
        }
    }

    @Test
    fun `token signed with different secret is rejected`() {
        val foreignAlgorithm = Algorithm.HMAC256("completely-different-secret-of-32-bytes-here")
        val foreignToken = JWT.create()
            .withIssuer(cfg.issuer)
            .withAudience(cfg.audience)
            .withSubject("u_attacker")
            .withExpiresAt(Instant.now().plusSeconds(60))
            .sign(foreignAlgorithm)

        shouldThrow<JWTVerificationException> {
            verifiers.accessVerifier.verify(foreignToken)
        }
    }

    @Test
    fun `token with wrong issuer is rejected`() {
        val token = JWT.create()
            .withIssuer("malicious-issuer")
            .withAudience(cfg.audience)
            .withSubject("u_test")
            .withExpiresAt(Instant.now().plusSeconds(60))
            .sign(verifiers.algorithm)

        shouldThrow<JWTVerificationException> {
            verifiers.accessVerifier.verify(token)
        }
    }

    @Test
    fun `expired token is rejected`() {
        val expired = JWT.create()
            .withIssuer(cfg.issuer)
            .withAudience(cfg.audience)
            .withSubject("u_test")
            .withIssuedAt(Instant.now().minusSeconds(1000))
            .withExpiresAt(Instant.now().minusSeconds(100))   // 100 секунд назад
            .sign(verifiers.algorithm)

        shouldThrow<JWTVerificationException> {
            verifiers.accessVerifier.verify(expired)
        }
    }

    @Test
    fun `refresh token has longer TTL than access`() {
        val accessToken = verifiers.issueAccessToken("u_test", "jti-a")
        val refreshToken = verifiers.issueRefreshToken("u_test", "jti-r")

        val accessExp = JWT.decode(accessToken).expiresAt.toInstant()
        val refreshExp = JWT.decode(refreshToken).expiresAt.toInstant()

        (refreshExp.epochSecond > accessExp.epochSecond) shouldBe true
    }
}
