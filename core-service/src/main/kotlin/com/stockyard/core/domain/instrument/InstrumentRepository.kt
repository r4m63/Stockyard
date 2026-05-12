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

    /** Полный каталог — для GET /v1/instruments. */
    fun listAll(conn: Connection): List<Instrument> =
        conn.prepareStatement(
            "SELECT ticker, name, type, lot_size FROM instruments ORDER BY ticker",
        ).use { ps ->
            ps.executeQuery().use { rs ->
                val acc = mutableListOf<Instrument>()
                while (rs.next()) {
                    acc += Instrument(
                        ticker = rs.getString("ticker"),
                        name = rs.getString("name"),
                        type = rs.getString("type"),
                        lotSize = rs.getInt("lot_size"),
                    )
                }
                acc
            }
        }
}
