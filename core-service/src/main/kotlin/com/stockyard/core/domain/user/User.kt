package com.stockyard.core.domain.user

import java.time.Instant

/**
 * Domain-модель пользователя. Хранит только то, что лежит в БД — без plaintext password.
 * Соответствует V1 миграции `users(id, email, password_hash, created_at)`.
 */
data class User(
    val id: String,
    val email: String,
    val passwordHash: String,
    val createdAt: Instant,
)
