package com.stockyard.gateway.test

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.azam.ulidj.ULID
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import java.time.Instant

/**
 * Issues a valid access JWT and seeds `session:{jti}` in Redis so the WS
 * handshake passes [com.stockyard.gateway.auth.SessionStore.accessSessionExists].
 *
 * Bypasses Core Service entirely — tests don't have a reachable Core in
 * `installTestModule` (`coreServiceBaseUrl = http://localhost:1` is sealed).
 */
object WsAuthFixture {

    const val DEFAULT_SECRET = "this-is-a-test-secret-32-bytes-min-length"
    const val DEFAULT_ISSUER = "stockyard-gateway"
    const val DEFAULT_AUDIENCE = "stockyard-clients"
    private const val DEFAULT_TTL_SECONDS = 900L

    data class IssuedToken(val token: String, val userId: String, val jti: String)

    fun issueAndSeed(
        redisUrl: String,
        userId: String = "u_${ULID.random()}",
        secret: String = DEFAULT_SECRET,
        ttlSeconds: Long = DEFAULT_TTL_SECONDS,
    ): IssuedToken {
        val jti = ULID.random()
        val now = Instant.now()
        val token = JWT.create()
            .withIssuer(DEFAULT_ISSUER)
            .withAudience(DEFAULT_AUDIENCE)
            .withSubject(userId)
            .withJWTId(jti)
            .withIssuedAt(now)
            .withExpiresAt(now.plusSeconds(ttlSeconds))
            .sign(Algorithm.HMAC256(secret))

        val client = RedisClient.create(RedisURI.create(redisUrl))
        try {
            client.connect().use { conn ->
                conn.sync().setex("session:$jti", ttlSeconds, userId)
            }
        } finally {
            client.shutdown()
        }
        return IssuedToken(token, userId, jti)
    }

    fun issueExpired(
        userId: String = "u_expired",
        secret: String = DEFAULT_SECRET,
    ): String = JWT.create()
        .withIssuer(DEFAULT_ISSUER)
        .withAudience(DEFAULT_AUDIENCE)
        .withSubject(userId)
        .withJWTId(ULID.random())
        .withIssuedAt(Instant.now().minusSeconds(3600))
        .withExpiresAt(Instant.now().minusSeconds(60))
        .sign(Algorithm.HMAC256(secret))

    /** Issue a JWT but DON'T seed Redis — simulates a revoked session. */
    fun issueWithoutSession(
        userId: String = "u_revoked",
        secret: String = DEFAULT_SECRET,
    ): String = JWT.create()
        .withIssuer(DEFAULT_ISSUER)
        .withAudience(DEFAULT_AUDIENCE)
        .withSubject(userId)
        .withJWTId(ULID.random())
        .withIssuedAt(Instant.now())
        .withExpiresAt(Instant.now().plusSeconds(DEFAULT_TTL_SECONDS))
        .sign(Algorithm.HMAC256(secret))

    /** Publish an ADR-011 cents-JSON Quote payload on `channel:quotes:{ticker}`. */
    fun publishQuote(
        redisUrl: String,
        ticker: String,
        bidCents: Long = 28550,
        askCents: Long = 28570,
        lastCents: Long = 28560,
        volume: Long = 12345,
        ts: String = "2026-05-09T12:34:56.789Z",
        tsNs: Long = 1_746_789_296_789_012_345L,
    ) {
        val payload = """
            {"ticker":"$ticker","ts":"$ts","tsNs":$tsNs,
             "bidCents":$bidCents,"askCents":$askCents,"lastCents":$lastCents,"volume":$volume}
        """.trimIndent().replace("\n", "").replace(" ", "")
        val client = RedisClient.create(RedisURI.create(redisUrl))
        try {
            client.connect().use { conn ->
                conn.sync().publish("channel:quotes:$ticker", payload)
            }
        } finally {
            client.shutdown()
        }
    }

    /** Pre-load `quotes:{ticker}` HASH with frozen C2 fields for snapshot tests. */
    fun seedQuoteHash(
        redisUrl: String,
        ticker: String,
        bidCents: Long = 28550,
        askCents: Long = 28570,
        lastCents: Long = 28560,
        volume: Long = 12345,
        ts: String = "2026-05-09T12:34:56.789Z",
        tsNs: Long = 1_746_789_296_789_012_345L,
    ) {
        val client = RedisClient.create(RedisURI.create(redisUrl))
        try {
            client.connect().use { conn ->
                conn.sync().hset(
                    "quotes:$ticker",
                    mapOf(
                        "ts" to ts,
                        "ts_ns" to tsNs.toString(),
                        "bid" to bidCents.toString(),
                        "ask" to askCents.toString(),
                        "last" to lastCents.toString(),
                        "volume" to volume.toString(),
                    ),
                )
            }
        } finally {
            client.shutdown()
        }
    }
}
