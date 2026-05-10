package com.stockyard.core.api

import com.stockyard.core.domain.order.Order
import com.stockyard.core.domain.order.OrderSide
import com.stockyard.core.domain.order.OrderStatus
import kotlinx.serialization.Serializable

@Serializable
data class InternalPlaceOrderRequest(
    val userId: String,
    val ticker: String,
    val side: String,           // "BUY" | "SELL" — валидируем в API
    val qty: Int,
    val idempotencyKey: String,
)

@Serializable
data class InternalPlaceOrderResponse(
    val orderId: String,
    val status: String,         // "EXECUTED" | "REJECTED"
    val ticker: String,
    val side: String,
    val qty: Int,
    val priceCents: Long?,
    val createdAt: String,
    val executedAt: String?,
)

@Serializable
data class InternalListOrdersResponse(
    val items: List<InternalPlaceOrderResponse>,
    val nextCursor: String?,
)

fun Order.toDto(): InternalPlaceOrderResponse = InternalPlaceOrderResponse(
    orderId = id,
    status = status.name,
    ticker = ticker,
    side = side.name,
    qty = qty,
    priceCents = priceCents,
    createdAt = createdAt.toString(),
    executedAt = executedAt?.toString(),
)

fun parseSide(raw: String): OrderSide = try {
    OrderSide.valueOf(raw.uppercase())
} catch (e: IllegalArgumentException) {
    throw IllegalArgumentException("invalid side: $raw (expected BUY or SELL)", e)
}

fun parseStatusFilter(raw: String?): OrderStatus? = raw?.takeIf { it.isNotBlank() }?.let {
    try {
        OrderStatus.valueOf(it.uppercase())
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("invalid status filter: $raw", e)
    }
}
