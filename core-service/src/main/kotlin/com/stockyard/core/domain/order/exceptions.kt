package com.stockyard.core.domain.order

/** Тикер не найден в каталоге `instruments`. */
class InvalidTickerException(val ticker: String) :
    RuntimeException("instrument not found: $ticker")

/** qty ≤ 0 или больше MAX_QTY. */
class InvalidQuantityException(val qty: Int) :
    RuntimeException("quantity out of range: $qty")

/** BUY: balance < cost. */
class InsufficientFundsException(val requiredCents: Long, val availableCents: Long) :
    RuntimeException("insufficient funds: need=$requiredCents have=$availableCents")

/** SELL: позиция меньше запрошенного qty (или позиции нет). */
class InsufficientPositionException(val requiredQty: Int, val availableQty: Int) :
    RuntimeException("insufficient position: need=$requiredQty have=$availableQty")

/** В Redis нет цены для тикера (до TASK-008 — если упал DevPriceFixture). */
class NoQuoteAvailableException(val ticker: String) :
    RuntimeException("no quote available for $ticker")

/** Повтор с тем же Idempotency-Key, но другим телом запроса. */
class IdempotencyConflictException(message: String = "idempotency key reused with different body") :
    RuntimeException(message)
