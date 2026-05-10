-- Dev seed: 5 тестовых пользователей с депозитом 1 000 000 RUB и одной позицией для smoke-тестов.
-- См. docs/architecture/12-storage-operations.md §12.1.4.
--
-- ВНИМАНИЕ: НЕ запускать на demo/prod. Только для локальной разработки.
-- Хэши паролей зависят от ARGON2_PEPPER, который генерируется при первом старте demo
-- и НЕ коммитится. В этом файле — заглушки, которые перезаписываются CLI-утилитой
-- `core-service/bin/seed-dev-users` (генерирует argon2id-хэш от 'test123' с актуальным pepper'ом).
--
-- Планируемый запуск:
--   make seed-dev          # читает .env, перегенерирует хэши, прогоняет этот SQL

INSERT INTO users (id, email, password_hash, created_at) VALUES
  ('u_dev0001', 'alice@stockyard.local',  '$argon2id$REPLACE_AT_RUNTIME', now()),
  ('u_dev0002', 'bob@stockyard.local',    '$argon2id$REPLACE_AT_RUNTIME', now()),
  ('u_dev0003', 'carol@stockyard.local',  '$argon2id$REPLACE_AT_RUNTIME', now()),
  ('u_dev0004', 'demo@stockyard.local',   '$argon2id$REPLACE_AT_RUNTIME', now()),
  ('u_dev0005', 'qa@stockyard.local',     '$argon2id$REPLACE_AT_RUNTIME', now())
ON CONFLICT (id) DO NOTHING;

-- Депозит 1 000 000 RUB = 100 000 000 cents у каждого dev-пользователя.
INSERT INTO accounts (user_id, balance_cents, currency)
SELECT id, 100000000, 'RUB' FROM users WHERE id LIKE 'u_dev%'
ON CONFLICT (user_id, currency) DO NOTHING;

-- alice уже владеет SBER × 100 (по средней 280.00 руб) — для smoke-теста SELL.
INSERT INTO positions (user_id, ticker, qty, avg_price_cents)
VALUES ('u_dev0001', 'SBER', 100, 28000)
ON CONFLICT (user_id, ticker) DO NOTHING;
