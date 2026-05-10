package com.stockyard.core.domain.instrument

/** Запись каталога инструментов (V2 миграция). */
data class Instrument(
    val ticker: String,
    val name: String,
    val type: String,
    val lotSize: Int,
)
