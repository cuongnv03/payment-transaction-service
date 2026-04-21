-- ─────────────────────────────────────────────────────────────────────────────
-- V2: Transactions
--
-- P2P transfer model: every transaction moves money from one account to another.
-- from_account_id = sender; to_account_id = receiver.
-- The diff_accounts constraint makes self-transfers structurally impossible at
-- the DB level — the application layer enforces the same rule first (fail fast).
--
-- version supports JPA @Version (optimistic locking): Hibernate adds
-- WHERE version=N on every UPDATE; a concurrent update increments version first,
-- causing our UPDATE to match 0 rows → OptimisticLockException.
--
-- retry_count: incremented by the Kafka consumer on each gateway retry attempt;
-- read by the monitoring layer and the circuit-breaker decision logic.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE transactions (
    id                UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    from_account_id   UUID           NOT NULL REFERENCES accounts (id),
    to_account_id     UUID           NOT NULL REFERENCES accounts (id),
    amount            NUMERIC(19, 4) NOT NULL,
    currency          VARCHAR(3)     NOT NULL DEFAULT 'USD',
    status            VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    description       VARCHAR(500),
    idempotency_key   VARCHAR(255),
    gateway_reference VARCHAR(255),
    failure_reason    VARCHAR(1000),
    retry_count       INT            NOT NULL DEFAULT 0,
    version           BIGINT         NOT NULL DEFAULT 0,
    processed_at      TIMESTAMPTZ,
    refunded_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_transactions_amount      CHECK (amount > 0),
    CONSTRAINT ck_transactions_status      CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCESS',
                                                             'FAILED', 'TIMEOUT', 'REFUNDED')),
    CONSTRAINT ck_transactions_diff_accounts CHECK (from_account_id != to_account_id)
);

-- Most frequent query: "show me my sent transactions, newest first".
CREATE INDEX idx_transactions_from_account_created ON transactions (from_account_id, created_at DESC);

-- Admin / monitoring query by status (hot path: PENDING + PROCESSING only).
CREATE INDEX idx_transactions_active_status ON transactions (status)
    WHERE status IN ('PENDING', 'PROCESSING');

-- Idempotency check on the write path — must be fast.
CREATE UNIQUE INDEX idx_transactions_idempotency ON transactions (idempotency_key)
    WHERE idempotency_key IS NOT NULL;
