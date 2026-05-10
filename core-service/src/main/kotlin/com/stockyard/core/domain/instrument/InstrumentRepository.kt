package com.stockyard.core.domain.instrument

import java.sql.Connection

/**
 * Read-only доступ к каталогу инструментов (`instruments`). 50 тикеров MOEX сидируются
 * V2 миграцией ([docs/architecture/seed/instruments-50.md]).
 */
class InstrumentRepository {

    /** Существует ли тикер в каталоге. Дёшево — PK на ticker. */
    fun existsTicker(conn: Connection, ticker: String): Boolean =
        conn.prepareStatement("SELECT 1 FROM instruments WHERE ticker = ?").use { ps ->
            ps.setString(1, ticker)
            ps.executeQuery().use { rs -> rs.next() }
        }

    /** Все тикеры — для DevPriceFixture. */
    fun listTickers(conn: Connection): List<String> =
        conn.prepareStatement("SELECT ticker FROM instruments ORDER BY ticker").use { ps ->
            ps.executeQuery().use { rs ->
                val acc = mutableListOf<String>()
                while (rs.next()) acc += rs.getString(1)
                acc
            }
        }
}
