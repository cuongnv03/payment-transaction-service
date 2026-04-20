-- ─────────────────────────────────────────────────────────────────────────────
-- V4: Audit Logs and Dead Letter Events
--
-- audit_logs         → immutable append-only record of every significant event.
--                      Required for financial compliance (who did what, when).
--                      Never UPDATE or DELETE rows — write new corrections instead.
--
-- dead_letter_events → Kafka messages that failed processing after max retries.
--                      Stored here so admins can inspect, retry, or discard them.
--                      resolved_at/resolved_by track the remediation audit trail.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE audit_logs (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    -- transaction_id is nullable: some events are user-level (login, account created)
    -- rather than transaction-scoped.
    transaction_id UUID,
    user_id        UUID         NOT NULL,
    event_type     VARCHAR(50)  NOT NULL,
    old_status     VARCHAR(20),
    new_status     VARCHAR(20),
    -- JSONB allows querying nested fields (e.g. metadata->>'ip') and GIN indexing
    -- if compliance queries require it in future.
    metadata       JSONB,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- All audit events for a specific transaction (core compliance query).
CREATE INDEX idx_audit_logs_transaction_id ON audit_logs (transaction_id)
    WHERE transaction_id IS NOT NULL;

-- Time-range compliance queries: "all events in this 30-day window".
CREATE INDEX idx_audit_logs_created_at     ON audit_logs (created_at DESC);

-- User-scoped audit trail.
CREATE INDEX idx_audit_logs_user_created   ON audit_logs (user_id, created_at DESC);

-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE dead_letter_events (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    topic            VARCHAR(255) NOT NULL,
    -- Kafka partition + offset identify the exact message for re-processing.
    -- Nullable because some error paths (e.g. deserialization) occur before
    -- offset is assigned.
    kafka_partition  INT,
    kafka_offset     BIGINT,
    payload          TEXT         NOT NULL,
    error_message    TEXT         NOT NULL,
    retry_count      INT          NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    resolved_at      TIMESTAMPTZ,
    resolved_by      VARCHAR(255)
);

-- Admin DLQ viewer primary view: unresolved events, newest first.
-- Partial index excludes already-resolved rows — keeps the index small.
CREATE INDEX idx_dlq_unresolved ON dead_letter_events (created_at DESC)
    WHERE resolved_at IS NULL;

-- Historical audit: all resolved events ordered by resolution time.
CREATE INDEX idx_dlq_resolved   ON dead_letter_events (resolved_at DESC)
    WHERE resolved_at IS NOT NULL;
