package com.stockyard.gateway.auth

/** Generic неверные creds на /v1/auth/login — НЕ различает «email не найден» и «пароль неверен». */
class InvalidCredentialsException : RuntimeException("invalid credentials")

/** UNIQUE(email) violation от core при register. */
class EmailTakenException : RuntimeException("email already registered")

/**
 * Подпись/exp/issuer/audience refresh-токена не прошли, либо jti отсутствует в Redis
 * (revoked / уже использован / устарел).
 */
class InvalidRefreshTokenException : RuntimeException("invalid refresh token")

/** Gateway DTO-level validation. */
class GatewayValidationException(val errorCode: String, message: String) : RuntimeException(message)

// ---- Orders (TASK-006) ----

/** Тот же Idempotency-Key с другим body. */
class IdempotencyConflictException : RuntimeException("idempotency key reused with different body")

/** Отсутствует обязательный заголовок `Idempotency-Key` для мутирующего POST. */
class MissingIdempotencyKeyException :
    RuntimeException("Idempotency-Key header is required for this endpoint")

class InsufficientFundsException(val requiredCents: Long, val availableCents: Long) :
    RuntimeException("insufficient funds")

class InsufficientPositionException(val requiredQty: Int, val availableQty: Int) :
    RuntimeException("insufficient position")

class InvalidTickerException(val ticker: String) : RuntimeException("invalid ticker: $ticker")

class InvalidQuantityException(val qty: Int) : RuntimeException("invalid quantity: $qty")

class NoQuoteAvailableException(val ticker: String) : RuntimeException("no quote: $ticker")

// ---- TASK-007: read-side ----

class InstrumentNotFoundException(val ticker: String) : RuntimeException("instrument not found: $ticker")

class InvalidIntervalException(val raw: String) : RuntimeException("invalid interval: $raw")

class InvalidTimeRangeException(val reason: String) : RuntimeException("invalid time range: $reason")
