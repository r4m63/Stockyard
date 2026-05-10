package com.stockyard.core.domain.order

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

/** Round-trip cursor encode/decode без БД. */
class OrderRepositoryCursorTest {

    @Test
    fun `encode then decode returns the same instant and order id`() {
        val ts = Instant.ofEpochMilli(1_700_000_000_123L)
        val orderId = "o_01HX7TKQAA7N0PZ8YX0AAB"
        val cursor = OrderRepository.encodeCursor(ts, orderId)
        val (decTs, decId) = OrderRepository.decodeCursor(cursor)
        decTs shouldBe ts
        decId shouldBe orderId
    }

    @Test
    fun `decode malformed cursor throws`() {
        val bad = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("nopipe".toByteArray())
        try {
            OrderRepository.decodeCursor(bad)
            error("expected exception")
        } catch (_: IllegalArgumentException) {
            // ожидаемо
        }
    }
}
