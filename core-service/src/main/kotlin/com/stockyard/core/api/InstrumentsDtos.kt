package com.stockyard.core.api

import com.stockyard.core.domain.instrument.Instrument
import kotlinx.serialization.Serializable

@Serializable
data class InternalInstrumentDto(
    val ticker: String,
    val name: String,
    val type: String,
    val lotSize: Int,
)

@Serializable
data class InternalInstrumentsResponse(val items: List<InternalInstrumentDto>)

fun Instrument.toDto(): InternalInstrumentDto =
    InternalInstrumentDto(ticker = ticker, name = name, type = type, lotSize = lotSize)
