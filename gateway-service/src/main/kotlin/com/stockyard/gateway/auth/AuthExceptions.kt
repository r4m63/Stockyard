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
