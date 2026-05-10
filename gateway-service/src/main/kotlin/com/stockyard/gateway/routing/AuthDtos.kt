package com.stockyard.gateway.routing

import kotlinx.serialization.Serializable

/**
 * DTO для /v1/auth/{register,login,refresh}. Контракты — docs/architecture/05-communication.md §5.3.2.
 */
@Serializable
data class RegisterRequest(val email: String, val password: String)

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

/** Ответ для login + refresh. */
@Serializable
data class TokenPairResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
)

/** Ответ для register — те же токены + userId. */
@Serializable
data class RegisterResponse(
    val userId: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
)
