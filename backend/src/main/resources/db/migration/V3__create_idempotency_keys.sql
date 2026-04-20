-- ─────────────────────────────────────────────────────────────────────────────
-- V3: Idempotency Keys
--
-- Stores the cached response for each Idempotency-Key header so that retried
-- requests receive the original response without re-executing the operation.
--
-- request_hash (SHA-256 of request body) guards against the misuse case where
-- a client reuses the same key with a different request — we detect this and
-- return 422 rather than silently returning the wrong cached response.
--
-- expires_at is set by the application (typically NOW() + 24h). A scheduled
-- job (Task 14) will periodically DELETE expired rows to keep the table small.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE idempotency_keys (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key VARCHAR(255) NOT NULL,
    user_id         UUID         NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    response_status INT          NOT NULL,
    response_body   TEXT         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ  NOT NULL,

    CONSTRAINT uq_idempotency_key         UNIQUE (idempotency_key),
    CONSTRAINT ck_idempotency_status_code CHECK  (response_status BETWEEN 100 AND 599)
);

-- Primary lookup on the write path: "has this key been used before?"
CREATE INDEX idx_idempotency_keys_key     ON idempotency_keys (idempotency_key);

-- Cleanup job: DELETE FROM idempotency_keys WHERE expires_at < NOW().
-- Plain index (no partial predicate) because PostgreSQL requires index predicates
-- to use only IMMUTABLE functions; NOW() is STABLE and is rejected at DDL time.
CREATE INDEX idx_idempotency_keys_expires ON idempotency_keys (expires_at);
