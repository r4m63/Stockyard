package com.stockyard.gateway.routing

import io.ktor.server.application.call

import kotlinx.serialization.Serializable

@Serializable
data class BalanceDto(val amountCents: Long, val currency: String)

@Serializable
data class PositionDto(
    val ticker: String,
    val qty: Int,
    val avgPriceCents: Long,
    val currentPriceCents: Long? = null,
    val unrealizedPnlCents: Long? = null,
)

@Serializable
data class PortfolioResponse(
    val balance: BalanceDto,
    val positions: List<PositionDto>,
)
