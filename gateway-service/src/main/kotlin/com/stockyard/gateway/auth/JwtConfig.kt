package com.stockyard.gateway.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.stockyard.gateway.config.JwtConfig
import java.time.Instant

class JwtVerifiers(private val cfg: JwtConfig) {
    val algorithm: Algorithm = Algorithm.HMAC256(cfg.secret)

    val accessVerifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(cfg.issuer)
        .withAudience(cfg.audience)
        .acceptLeeway(5)
        .build()

    fun issueAccessToken(userId: String, jti: String): String =
        JWT.create()
            .withIssuer(cfg.issuer)
            .withAudience(cfg.audience)
            .withSubject(userId)
            .withJWTId(jti)
            .withIssuedAt(Instant.now())
            .withExpiresAt(Instant.now().plusSeconds(cfg.accessTtlSeconds))
            .sign(algorithm)

    fun issueRefreshToken(userId: String, jti: String): String =
        JWT.create()
            .withIssuer(cfg.issuer)
            .withAudience(cfg.audience)
            .withSubject(userId)
            .withJWTId(jti)
            .withIssuedAt(Instant.now())
            .withExpiresAt(Instant.now().plusSeconds(cfg.refreshTtlSeconds))
            .sign(algorithm)
}
