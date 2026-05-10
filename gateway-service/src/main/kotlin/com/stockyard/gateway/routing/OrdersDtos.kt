package com.stockyard.gateway.routing

import kotlinx.serialization.Serializable

/** Public DTO. См. 05-communication.md §5.3.2 и TASK-006 §3.1. */

@Serializable
data class PlaceOrderRequest(
    val ticker: String,
    val side: String,        // "BUY" | "SELL"
    val qty: Int,
)

@Serializable
data class PlaceOrderResponse(
    val orderId: String,
    val status: String,      // "EXECUTED" — REJECTED идёт через exception → 422
    val ticker: String,
    val side: String,
    val qty: Int,
    val priceCents: Long?,
    val createdAt: String,
    val executedAt: String?,
)

@Serializable
data class OrderItemDto(
    val orderId: String,
    val status: String,
    val ticker: String,
    val side: String,
    val qty: Int,
    val priceCents: Long?,
    val createdAt: String,
    val executedAt: String?,
)

@Serializable
data class ListOrdersResponse(
    val items: List<OrderItemDto>,
    val nextCursor: String?,
)
