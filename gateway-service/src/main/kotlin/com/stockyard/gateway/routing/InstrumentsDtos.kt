package com.stockyard.gateway.routing

import io.ktor.server.application.call

import kotlinx.serialization.Serializable

@Serializable
data class InstrumentDto(
    val ticker: String,
    val name: String,
    val type: String,
    val lotSize: Int,
)

@Serializable
data class InstrumentsResponse(val items: List<InstrumentDto>)
