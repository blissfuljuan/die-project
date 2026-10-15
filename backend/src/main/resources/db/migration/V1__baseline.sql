-- V1 baseline: cross-cutting infrastructure tables. Domain tables arrive with their phases.

-- Append-only audit trail (submissions, reviews, approvals, publications, admin changes).
CREATE TABLE audit_log (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_user_id  UUID,
    action         TEXT NOT NULL,
    resource_type  TEXT NOT NULL,
    resource_id    TEXT,
    details        JSONB NOT NULL DEFAULT '{}'::jsonb,
    request_id     TEXT
);
CREATE INDEX idx_audit_log_resource ON audit_log (resource_type, resource_id);
CREATE INDEX idx_audit_log_actor    ON audit_log (actor_user_id, occurred_at DESC);

-- Transactional outbox: rows are written in the same transaction as the state change,
-- then relayed to RabbitMQ with publisher confirms.
CREATE TABLE outbox_event (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    aggregate_type  TEXT NOT NULL,
    aggregate_id    TEXT NOT NULL,
    exchange        TEXT NOT NULL,
    routing_key     TEXT NOT NULL,
    payload         JSONB NOT NULL,
    status          TEXT NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    attempts        INTEGER NOT NULL DEFAULT 0,
    last_error      TEXT,
    published_at    TIMESTAMPTZ
);
CREATE INDEX idx_outbox_pending ON outbox_event (created_at) WHERE status = 'PENDING';

-- Audit rows are immutable for the application identity (role exists in compose/Supabase setups).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'die_app') THEN
        REVOKE UPDATE, DELETE, TRUNCATE ON audit_log FROM die_app;
    END IF;
END
$$;
