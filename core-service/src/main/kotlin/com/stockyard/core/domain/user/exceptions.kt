package com.stockyard.core.domain.user

/** Бросается репозиторием при нарушении UNIQUE(users.email) — PG SQLState 23505. */
class EmailTakenException(email: String) : RuntimeException("email taken: $email")

/** Бизнес-валидация: email/password не соответствуют формату/длине. */
class ValidationException(val errorCode: String, message: String) : RuntimeException(message)
