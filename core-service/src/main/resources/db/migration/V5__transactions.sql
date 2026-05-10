-- V5: История денежных операций (audit trail)
-- См. docs/architecture/06-data.md §6.2.2 и §6.5 (никогда не удаляется).
-- amount_cents: отрицательная при BUY (списание), положительная при SELL/DEPOSIT.

CREATE TABLE transactions (
    id              BIGSERIAL PRIMARY KEY,
    user_id         TEXT NOT NULL REFERENCES users(id),
    type            TEXT NOT NULL CHECK (type IN ('DEPOSIT','BUY','SELL')),
    amount_cents    BIGINT NOT NULL,
    ref_order_id    TEXT REFERENCES orders(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_txn_user ON transactions(user_id, created_at DESC);
