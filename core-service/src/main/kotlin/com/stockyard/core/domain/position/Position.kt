package com.stockyard.core.domain.position

/**
 * Domain-модель позиции. PK (user_id, ticker) — см. V4 миграцию.
 * `avgPriceCents` — взвешенная средняя цена покупки, не меняется при SELL.
 */
data class Position(
    val userId: String,
    val ticker: String,
    val qty: Int,
    val avgPriceCents: Long,
)
