-- V8 (P0-VS04): transactional outbox relay support, consumer inbox (idempotency) and the minimal test_job
-- used to prove API -> RabbitMQ -> workers -> API connectivity. test_job is infrastructure, not project content.

-- outbox_event (V1) gains what the relay needs. payload holds the complete, versioned message envelope, and
-- outbox_event.id is the envelope's messageId, so a re-publication after a crash is recognisable downstream.
ALTER TABLE outbox_event
    ADD COLUMN message_type    TEXT,
    ADD COLUMN correlation_id  TEXT,
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN locked_until    TIMESTAMPTZ;

DROP INDEX idx_outbox_pending;
CREATE INDEX idx_outbox_pending ON outbox_event (next_attempt_at, created_at) WHERE status = 'PENDING';

-- Authoritative state of a test job (the queue is never the source of truth).
CREATE TABLE test_job (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    state            TEXT NOT NULL DEFAULT 'QUEUED'
                     CHECK (state IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    note             TEXT,
    simulate_failure BOOLEAN NOT NULL DEFAULT false,
    requested_by     UUID REFERENCES app_user (id) ON DELETE SET NULL,
    correlation_id   TEXT NOT NULL,
    result           JSONB,
    failure_reason   TEXT,
    last_message_id  UUID,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at       TIMESTAMPTZ,
    finished_at      TIMESTAMPTZ,
    CHECK ((state IN ('SUCCEEDED', 'FAILED')) = (finished_at IS NOT NULL))
);
CREATE INDEX idx_test_job_created ON test_job (created_at DESC);

-- Consumer dedupe: one row per (consumer, messageId) that was fully handled. Inserted in the same transaction
-- as the state change, so a duplicate delivery finds the row and is acknowledged without effect.
CREATE TABLE inbox_message (
    consumer     TEXT NOT NULL,
    message_id   UUID NOT NULL,
    received_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, message_id)
);

-- Least privilege for the runtime role: only the DML each table needs. (V4's default privileges grant all four
-- verbs on new tables; narrow them here. The role is created by infrastructure, so this is a no-op without it.)
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'die_app') THEN
        REVOKE DELETE, TRUNCATE ON outbox_event, test_job, inbox_message FROM die_app;
        REVOKE UPDATE ON inbox_message FROM die_app;
    END IF;
END
$$;
