-- V4: Позиции пользователя по тикерам
-- См. docs/architecture/06-data.md §6.2.2.
-- Одна строка на (user_id, ticker), хранит qty и среднюю цену покупки.
-- Обновление через INSERT ... ON CONFLICT DO UPDATE (см. §7.2.3).

CREATE TABLE positions (
    user_id             TEXT NOT NULL REFERENCES users(id),
    ticker              TEXT NOT NULL REFERENCES instruments(ticker),
    qty                 INT NOT NULL DEFAULT 0,
    avg_price_cents     BIGINT NOT NULL DEFAULT 0,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, ticker)
);
