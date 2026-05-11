package com.stockyard.gateway.routing

import io.ktor.server.application.call

import kotlinx.serialization.Serializable

@Serializable
data class QuoteResponse(
    val ticker: String,
    val bidCents: Long,
    val askCents: Long,
    val lastCents: Long,
    val ts: String,
)

@Serializable
data class CandleDto(
    val ts: String,
    val openCents: Long,
    val highCents: Long,
    val lowCents: Long,
    val closeCents: Long,
    val volume: Long,
)

@Serializable
data class CandlesResponse(
    val ticker: String,
    val interval: String,
    val candles: List<CandleDto>,
)
