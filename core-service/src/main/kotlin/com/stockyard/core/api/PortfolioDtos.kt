package com.stockyard.core.api

import com.stockyard.core.domain.portfolio.Portfolio
import kotlinx.serialization.Serializable

@Serializable
data class InternalBalanceDto(val amountCents: Long, val currency: String)

@Serializable
data class InternalPositionDto(
    val ticker: String,
    val qty: Int,
    val avgPriceCents: Long,
    val currentPriceCents: Long?,
    val unrealizedPnlCents: Long?,
)

@Serializable
data class InternalPortfolioResponse(
    val balance: InternalBalanceDto,
    val positions: List<InternalPositionDto>,
)

fun Portfolio.toDto(): InternalPortfolioResponse = InternalPortfolioResponse(
    balance = InternalBalanceDto(balance.amountCents, balance.currency),
    positions = positions.map {
        InternalPositionDto(
            ticker = it.ticker,
            qty = it.qty,
            avgPriceCents = it.avgPriceCents,
            currentPriceCents = it.currentPriceCents,
            unrealizedPnlCents = it.unrealizedPnlCents,
        )
    },
)
