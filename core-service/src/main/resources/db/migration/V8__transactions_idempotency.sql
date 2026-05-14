-- V8: Idempotency-Key support for `transactions`.
--
-- See ADR-011 (TASK-014): mirrors ADR-005 idempotency on `orders`, but the
-- UNIQUE constraint is partial — legacy rows (BUY/SELL audit from TASK-006)
-- have no key, and the constraint must not block them. The constraint is
-- also type-scoped so a deposit idempotency key namespace cannot collide
-- with an order's BUY/SELL key namespace.

ALTER TABLE transactions
    ADD COLUMN idempotency_key TEXT;

CREATE UNIQUE INDEX uq_transactions_user_type_idem
    ON transactions(user_id, type, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
