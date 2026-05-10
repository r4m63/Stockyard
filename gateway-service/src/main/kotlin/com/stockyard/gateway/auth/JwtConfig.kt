package com.stockyard.gateway.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import com.stockyard.gateway.config.JwtConfig
import java.time.Instant

class JwtVerifiers(private val cfg: JwtConfig) {
    val algorithm: Algorithm = Algorithm.HMAC256(cfg.secret)

    val accessVerifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(cfg.issuer)
        .withAudience(cfg.audience)
        .acceptLeeway(5)
        .build()

    /**
     * Отдельный верификатор для refresh-токенов. Подпись и issuer/audience совпадают
     * с access-токеном, но verify-flow разный: refresh идёт только в /v1/auth/refresh,
     * проверяется через [SessionStore.refreshSessionExists] перед ротацией.
     */
    val refreshVerifier: JWTVerifier = JWT.require(algorithm)
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

    /**
     * Верифицирует refresh-токен и возвращает (subject=userId, jti).
     * @throws JWTVerificationException — любая неудача верификации (подпись/exp/issuer).
     */
    fun verifyRefresh(token: String): DecodedJWT = refreshVerifier.verify(token)
}
