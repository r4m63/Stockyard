package com.stockyard.core.domain.portfolio

import com.stockyard.core.domain.account.AccountRepository
import com.stockyard.core.domain.position.Position
import com.stockyard.core.domain.position.PositionRepository
import com.stockyard.core.persistence.DataSources
import com.stockyard.core.quotes.QuotesPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Application-service для GET /v1/portfolio: баланс RUB + список позиций с обогащением
 * текущей ценой из Redis и расчётом unrealized P&L. Read-only flow, без TX-блокировок.
 */
class PortfolioService(
    private val dataSources: DataSources,
    private val accounts: AccountRepository,
    private val positions: PositionRepository,
    private val quotesPort: QuotesPort,
) {

    suspend fun getPortfolio(userId: String): Portfolio = coroutineScope {
        // Параллельно: PG-SELECT баланса + PG-SELECT позиций.
        val balanceDeferred = async(Dispatchers.IO) {
            dataSources.pg.connection.use { conn ->
                accounts.findBalance(conn, userId, "RUB") ?: 0L
            }
        }
        val positionsDeferred = async(Dispatchers.IO) {
            dataSources.pg.connection.use { conn -> positions.listByUser(conn, userId) }
        }
        val balanceCents = balanceDeferred.await()
        val rawPositions = positionsDeferred.await()

        // Обогащение позиций current price из Redis. Lettuce-pool — синхронный borrow,
        // делаем последовательно (под maxTotal=32 пул не перегрузит).
        val enriched = withContext(Dispatchers.IO) {
            rawPositions.map { pos -> enrich(pos) }
        }

        Portfolio(
            balance = Balance(amountCents = balanceCents, currency = "RUB"),
            positions = enriched,
        )
    }

    private fun enrich(p: Position): EnrichedPosition {
        val currentLastCents = quotesPort.getQuote(p.ticker)?.lastCents
        val unrealizedPnlCents = currentLastCents?.let { (it - p.avgPriceCents) * p.qty }
        return EnrichedPosition(
            ticker = p.ticker,
            qty = p.qty,
            avgPriceCents = p.avgPriceCents,
            currentPriceCents = currentLastCents,
            unrealizedPnlCents = unrealizedPnlCents,
        )
    }
}

data class Portfolio(
    val balance: Balance,
    val positions: List<EnrichedPosition>,
)

data class Balance(
    val amountCents: Long,
    val currency: String,
)

data class EnrichedPosition(
    val ticker: String,
    val qty: Int,
    val avgPriceCents: Long,
    val currentPriceCents: Long?,
    val unrealizedPnlCents: Long?,
)
