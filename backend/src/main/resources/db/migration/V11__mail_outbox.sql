-- Durable outgoing email queue. A service inserts the row in the same transaction as its state change, so mail is
-- neither lost on a crash after commit nor sent for a transaction that rolled back, and the request thread never waits
-- for SMTP (which would make "unknown address" and "known address" answer at different speeds).
-- Rows hold the message body, which can contain a single-use link: they are deleted as soon as the message is sent
-- (or after the last attempt), never kept as history.
CREATE TABLE mail_outbox (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    to_address      TEXT NOT NULL,
    subject         TEXT NOT NULL,
    body            TEXT NOT NULL,
    attempts        INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_mail_outbox_due ON mail_outbox (next_attempt_at, created_at);
