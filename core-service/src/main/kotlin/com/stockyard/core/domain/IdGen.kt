package com.stockyard.core.domain

import io.azam.ulidj.ULID

/**
 * Префиксированные ULID для бизнес-сущностей (CLAUDE.md «Конвенции/Идентификаторы»).
 * Текстовые, монотонно растущие во времени, безопасны для показа в API.
 */
object IdGen {
    fun userId(): String = "u_" + ULID.random()
    fun orderId(): String = "o_" + ULID.random()
}
