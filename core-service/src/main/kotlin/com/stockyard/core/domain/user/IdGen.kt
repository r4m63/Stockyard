package com.stockyard.core.domain.user

import io.azam.ulidj.ULID

/**
 * ULID-генератор для идентификаторов с префиксом по CLAUDE.md «Конвенции/Идентификаторы».
 * users → `u_<26 chars Crockford Base32>` (26 + 2 = 28 chars). Хранится в `users.id TEXT`.
 */
object IdGen {
    fun userId(): String = "u_" + ULID.random()
}
