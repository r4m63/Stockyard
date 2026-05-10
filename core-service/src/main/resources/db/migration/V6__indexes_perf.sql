-- V6: Дополнительные индексы под UI-запросы
-- См. docs/architecture/12-storage-operations.md §12.1.5.

-- "История ордеров пользователя по конкретному тикеру" — экран позиции на мобильном клиенте.
CREATE INDEX idx_orders_user_ticker ON orders(user_id, ticker, created_at DESC);
