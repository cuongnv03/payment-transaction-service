-- ─────────────────────────────────────────────────────────────────────────────
-- V2: Transactions
--
-- Central entity. The status column enforces the state machine at the DB level
-- as a secondary safety net — the application enforces the same transitions.
-- Having the constraint in both layers means a bug in one cannot corrupt the
-- other; any invalid state is caught at whichever boundary it reaches first.
--
-- State machine: PENDING → PROCESSING → SUCCESS | FAILED | TIMEOUT → REFUNDED
--
-- version supports JPA @Version (optimistic locking) for concurrent read-modify-
-- write without full row locking — appropriate here because most concurrent
-- updates are to different transactions, so lock contention is low.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE transactions (
    id                UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID           NOT NULL,
    account_id        UUID           NOT NULL,
    amount            NUMERIC(19, 4) NOT NULL,
    currency          VARCHAR(3)     NOT NULL DEFAULT 'USD',
    type              VARCHAR(20)    NOT NULL,
    status            VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    description       VARCHAR(500),
    idempotency_key   VARCHAR(255),
    gateway_reference VARCHAR(255),
    failure_reason    TEXT,
    version           BIGINT         NOT NULL DEFAULT 0,
    processed_at      TIMESTAMPTZ,
    refunded_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_transactions_user    FOREIGN KEY (user_id)    REFERENCES users    (id),
    CONSTRAINT fk_transactions_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT ck_transactions_amount  CHECK (amount > 0),
    CONSTRAINT ck_transactions_type    CHECK (type   IN ('PAYMENT', 'DEPOSIT', 'TRANSFER')),
    CONSTRAINT ck_transactions_status  CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCESS',
                                                         'FAILED', 'TIMEOUT', 'REFUNDED'))
);

-- Most frequent query: "show me my transactions, newest first".
-- Composite index on (user_id, created_at DESC) covers this in one index scan.
CREATE INDEX idx_transactions_user_created   ON transactions (user_id, created_at DESC);

-- Partial index: only PENDING and PROCESSING rows need fast status lookups
-- (monitoring, stuck-transaction detection). Terminal states are cold data.
CREATE INDEX idx_transactions_active_status  ON transactions (status)
    WHERE status IN ('PENDING', 'PROCESSING');

-- Idempotency check on the write path — must be fast. Partial index keeps it
-- small by excluding rows where idempotency_key is not set (e.g. DEPOSIT).
CREATE INDEX idx_transactions_idempotency    ON transactions (idempotency_key)
    WHERE idempotency_key IS NOT NULL;
