package com.stockyard.core.domain.order

import java.time.Instant

enum class OrderSide { BUY, SELL }

enum class OrderStatus { PENDING, EXECUTED, REJECTED }

/**
 * Domain-модель ордера. Соответствует V3 миграции
 * `orders(id, user_id, ticker, side, qty, price_cents, status, idempotency_key, created_at, executed_at)`.
 *
 * Деньги — `Long` cents (CLAUDE.md «Конвенции/Деньги»).
 * `priceCents` == null до исполнения (PENDING), иначе цена исполнения (EXECUTED или REJECTED).
 */
data class Order(
    val id: String,
    val userId: String,
    val ticker: String,
    val side: OrderSide,
    val qty: Int,
    val priceCents: Long?,
    val status: OrderStatus,
    val idempotencyKey: String,
    val createdAt: Instant,
    val executedAt: Instant?,
)
