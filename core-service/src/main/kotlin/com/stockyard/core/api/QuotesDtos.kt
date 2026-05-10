package com.stockyard.core.api

import com.stockyard.core.domain.quotes.HistoryResult
import com.stockyard.core.quotes.Candle
import com.stockyard.core.quotes.Quote
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class InternalQuoteResponse(
    val ticker: String,
    val bidCents: Long,
    val askCents: Long,
    val lastCents: Long,
    val ts: String,        // ISO-8601
)

@Serializable
data class InternalCandleDto(
    val ts: String,        // ISO-8601 (start of bucket)
    val openCents: Long,
    val highCents: Long,
    val lowCents: Long,
    val closeCents: Long,
    val volume: Long,
)

@Serializable
data class InternalCandlesResponse(
    val ticker: String,
    val interval: String,
    val candles: List<InternalCandleDto>,
)

fun Quote.toDto(ticker: String): InternalQuoteResponse = InternalQuoteResponse(
    ticker = ticker,
    bidCents = bidCents,
    askCents = askCents,
    lastCents = lastCents,
    ts = Instant.ofEpochMilli(tsEpochMs).toString(),
)

fun Candle.toDto(): InternalCandleDto = InternalCandleDto(
    ts = Instant.ofEpochMilli(tsEpochMs).toString(),
    openCents = openCents,
    highCents = highCents,
    lowCents = lowCents,
    closeCents = closeCents,
    volume = volume,
)

fun HistoryResult.toDto(): InternalCandlesResponse = InternalCandlesResponse(
    ticker = ticker,
    interval = when (interval) {
        com.stockyard.core.quotes.CandleInterval.M1 -> "1m"
        com.stockyard.core.quotes.CandleInterval.H1 -> "1h"
    },
    candles = candles.map { it.toDto() },
)
