-- ─────────────────────────────────────────────────────────────────────────────
-- V1: Users and Accounts
--
-- users    → identity and auth (JWT principal source)
-- accounts → monetary balance; one per user, one-to-one enforced by UNIQUE
--
-- Money stored as NUMERIC(19,4) — never FLOAT (floating-point rounding corrupts
-- financial calculations). TIMESTAMPTZ stores UTC; the app layer converts to
-- local time, keeping the DB timezone-agnostic.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE users (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    username      VARCHAR(50)  NOT NULL,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_users_username UNIQUE (username),
    CONSTRAINT uq_users_email    UNIQUE (email),
    CONSTRAINT ck_users_role     CHECK  (role IN ('USER', 'ADMIN'))
);

-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE accounts (
    id         UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID           NOT NULL,
    balance    NUMERIC(19, 4) NOT NULL DEFAULT 0.0000,
    currency   VARCHAR(3)     NOT NULL DEFAULT 'USD',
    -- version supports optimistic locking (@Version in JPA) for read-heavy paths,
    -- but balance deductions use SELECT FOR UPDATE (pessimistic) to prevent
    -- overdraft under concurrent updates.
    version    BIGINT         NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_accounts_user    FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uq_accounts_user_id UNIQUE (user_id),
    CONSTRAINT ck_accounts_balance CHECK  (balance >= 0)
);

-- Covered by UNIQUE constraint above, but explicit index improves readability
-- in EXPLAIN plans and is used by the pessimistic-lock query path.
CREATE INDEX idx_accounts_user_id ON accounts (user_id);
