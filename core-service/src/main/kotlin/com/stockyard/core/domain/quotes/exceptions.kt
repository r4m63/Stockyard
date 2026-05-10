package com.stockyard.core.domain.quotes

/** Тикер запрошен, но его нет в каталоге `instruments`. → 404. */
class InstrumentNotFoundException(val ticker: String) :
    RuntimeException("instrument not found: $ticker")

/** Interval не в `{1m, 1h}`. → 422. */
class InvalidIntervalException(val raw: String) :
    RuntimeException("invalid interval: $raw (expected 1m or 1h)")

/** from >= to, либо span превышает лимит для интервала. → 422. */
class InvalidTimeRangeException(reason: String) :
    RuntimeException("invalid time range: $reason")
