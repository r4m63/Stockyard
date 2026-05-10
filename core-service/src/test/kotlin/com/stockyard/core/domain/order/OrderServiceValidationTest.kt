package com.stockyard.core.domain.order

import com.stockyard.core.domain.account.AccountRepository
import com.stockyard.core.domain.instrument.InstrumentRepository
import com.stockyard.core.domain.position.PositionRepository
import com.stockyard.core.domain.transaction.TransactionRepository
import com.stockyard.core.persistence.TransactionManager
import com.stockyard.core.quotes.QuotesPort
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Unit-тесты валидации в [OrderService.place]. Все коллабораторы — моки.
 * Эти ветки бросают ДО входа в `tx.withTx`, поэтому никакие методы моков не вызываются.
 */
class OrderServiceValidationTest {

    private val tx = mockk<TransactionManager>()
    private val instruments = mockk<InstrumentRepository>()
    private val orders = mockk<OrderRepository>()
    private val accounts = mockk<AccountRepository>()
    private val positions = mockk<PositionRepository>()
    private val transactions = mockk<TransactionRepository>()
    private val quotes = mockk<QuotesPort>()

    private val service = OrderService(tx, instruments, orders, accounts, positions, transactions, quotes)

    @Test
    fun `qty zero is rejected as INVALID_QUANTITY`() = runTest {
        val ex = shouldThrow<InvalidQuantityException> {
            service.place("u_test", "SBER", OrderSide.BUY, 0, "k1")
        }
        ex.qty shouldBe 0
    }

    @Test
    fun `qty negative is rejected`() = runTest {
        shouldThrow<InvalidQuantityException> {
            service.place("u_test", "SBER", OrderSide.BUY, -5, "k1")
        }
    }

    @Test
    fun `qty above MAX is rejected`() = runTest {
        shouldThrow<InvalidQuantityException> {
            service.place("u_test", "SBER", OrderSide.BUY, OrderService.MAX_QTY + 1, "k1")
        }
    }

    @Test
    fun `blank idempotency key is rejected as IllegalArgumentException`() = runTest {
        shouldThrow<IllegalArgumentException> {
            service.place("u_test", "SBER", OrderSide.BUY, 10, "  ")
        }
    }

    @Test
    fun `no quote available short-circuits before TX`() = runTest {
        coEvery { quotes.getAsk("SBER") } returns null
        val ex = shouldThrow<NoQuoteAvailableException> {
            service.place("u_test", "SBER", OrderSide.BUY, 10, "k1")
        }
        ex.ticker shouldBe "SBER"
    }

    @Test
    fun `no bid for SELL short-circuits`() = runTest {
        coEvery { quotes.getBid("GAZP") } returns null
        shouldThrow<NoQuoteAvailableException> {
            service.place("u_test", "GAZP", OrderSide.SELL, 1, "k2")
        }
    }
}
