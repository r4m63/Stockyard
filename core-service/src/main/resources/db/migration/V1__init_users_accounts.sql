-- V1: Пользователи и денежные счета
-- См. docs/architecture/06-data.md §6.2.2.

CREATE TABLE users (
    id              TEXT PRIMARY KEY,                    -- u_<ulid>
    email           TEXT UNIQUE NOT NULL,
    password_hash   TEXT NOT NULL,                       -- argon2id, см. ADR-006
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE accounts (
    id              BIGSERIAL PRIMARY KEY,
    user_id         TEXT NOT NULL REFERENCES users(id),
    balance_cents   BIGINT NOT NULL DEFAULT 0,
    currency        TEXT NOT NULL DEFAULT 'RUB',
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, currency)
);

CREATE INDEX idx_accounts_user ON accounts(user_id);
