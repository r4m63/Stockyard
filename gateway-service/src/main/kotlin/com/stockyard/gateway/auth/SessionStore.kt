package com.stockyard.gateway.auth

import com.stockyard.gateway.redis.RedisModule
import org.slf4j.LoggerFactory

/**
 * Redis-обёртка для access/refresh sessions.
 *
 * Ключи (docs/architecture/06-data.md §6.3.2):
 *  - `session:{access_jti}` STRING, TTL = accessTtlSeconds, value = userId
 *  - `refresh:{refresh_jti}` STRING, TTL = refreshTtlSeconds, value = userId
 *
 * Access-session проверяется на каждом authenticated-запросе (revoke capability).
 * Refresh-session проверяется + ротируется на /v1/auth/refresh.
 */
class SessionStore(private val redis: RedisModule) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun storeAccessSession(jti: String, userId: String, ttlSeconds: Long) {
        redis.withCommandConnection { it.sync().setex(accessKey(jti), ttlSeconds, userId) }
    }

    fun storeRefreshSession(jti: String, userId: String, ttlSeconds: Long) {
        redis.withCommandConnection { it.sync().setex(refreshKey(jti), ttlSeconds, userId) }
    }

    fun accessSessionExists(jti: String): Boolean =
        redis.withCommandConnection { it.sync().exists(accessKey(jti)) > 0 }

    fun refreshSessionExists(jti: String): Boolean =
        redis.withCommandConnection { it.sync().exists(refreshKey(jti)) > 0 }

    /** Удаляет refresh-токен (rotation на /v1/auth/refresh). Idempotent. */
    fun deleteRefreshSession(jti: String) {
        val removed = redis.withCommandConnection { it.sync().del(refreshKey(jti)) }
        if (removed == 0L) {
            log.atDebug().addKeyValue("jti", jti).log("refresh session already gone on delete")
        }
    }

    private fun accessKey(jti: String) = "session:$jti"
    private fun refreshKey(jti: String) = "refresh:$jti"
}
