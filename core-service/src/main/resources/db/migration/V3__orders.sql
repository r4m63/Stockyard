-- V3: Ордера + UNIQUE по (user_id, idempotency_key)
-- См. docs/architecture/06-data.md §6.2.2,
--     docs/architecture/07-consistency.md §7.2,
--     ADR-005 (idempotency-key), ADR-007 (бессрочное хранение ключа).

CREATE TABLE orders (
    id                  TEXT PRIMARY KEY,                                  -- o_<ulid>
    user_id             TEXT NOT NULL REFERENCES users(id),
    ticker              TEXT NOT NULL REFERENCES instruments(ticker),
    side                TEXT NOT NULL CHECK (side IN ('BUY','SELL')),
    qty                 INT NOT NULL CHECK (qty > 0),
    price_cents         BIGINT,                                            -- NULL до исполнения
    status              TEXT NOT NULL CHECK (status IN ('PENDING','EXECUTED','REJECTED')),
    idempotency_key     TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    executed_at         TIMESTAMPTZ,
    UNIQUE (user_id, idempotency_key)
);

CREATE INDEX idx_orders_user_created ON orders(user_id, created_at DESC);
CREATE INDEX idx_orders_status ON orders(status) WHERE status = 'PENDING';
