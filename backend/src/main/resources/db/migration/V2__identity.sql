-- V2: identity and credentials.

CREATE TABLE app_user (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    role              TEXT NOT NULL CHECK (role IN ('STUDENT', 'INSTRUCTOR', 'ADMIN')),
    status            TEXT NOT NULL DEFAULT 'PENDING_ACTIVATION'
                      CHECK (status IN ('PENDING_ACTIVATION', 'ACTIVE', 'DEACTIVATED')),
    student_number    TEXT,
    last_name         TEXT NOT NULL,
    first_name        TEXT NOT NULL,
    middle_name       TEXT,
    email             TEXT,
    email_verified_at TIMESTAMPTZ,
    password_hash     TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    version           BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_student_has_number CHECK (role <> 'STUDENT' OR student_number IS NOT NULL),
    CONSTRAINT chk_active_has_login CHECK (
        status <> 'ACTIVE' OR (email IS NOT NULL AND email_verified_at IS NOT NULL AND password_hash IS NOT NULL))
);
CREATE UNIQUE INDEX uq_app_user_student_number ON app_user (student_number) WHERE student_number IS NOT NULL;
CREATE UNIQUE INDEX uq_app_user_email ON app_user (lower(email)) WHERE email IS NOT NULL;

-- Per-user activation/reset credential. Only the hash is stored.
CREATE TABLE temp_credential (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    secret_hash     TEXT NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    invalidated_at  TIMESTAMPTZ,
    invalid_reason  TEXT CHECK (invalid_reason IN ('ACTIVATED', 'RESET', 'EXPIRED', 'LOCKED')),
    created_by      UUID REFERENCES app_user (id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- At most one live temporary credential per user.
CREATE UNIQUE INDEX uq_temp_credential_active ON temp_credential (user_id) WHERE invalidated_at IS NULL;

CREATE TABLE email_verification_token (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    email       TEXT NOT NULL,
    token_hash  TEXT NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_email_verification_user ON email_verification_token (user_id);

CREATE TABLE password_reset_token (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    token_hash  TEXT NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_password_reset_user ON password_reset_token (user_id);

-- Login throttling (per identifier and per client address).
CREATE TABLE login_attempt (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    identifier   TEXT NOT NULL,
    client_ip    TEXT,
    succeeded    BOOLEAN NOT NULL,
    attempted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_login_attempt_identifier ON login_attempt (identifier, attempted_at DESC);
CREATE INDEX idx_login_attempt_ip ON login_attempt (client_ip, attempted_at DESC);

-- Spring Session JDBC (server-managed sessions shared across API instances).
CREATE TABLE spring_session (
    primary_id            CHAR(36) NOT NULL,
    session_id            CHAR(36) NOT NULL,
    creation_time         BIGINT NOT NULL,
    last_access_time      BIGINT NOT NULL,
    max_inactive_interval INT NOT NULL,
    expiry_time           BIGINT NOT NULL,
    principal_name        VARCHAR(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);
CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
    session_primary_id CHAR(36) NOT NULL,
    attribute_name     VARCHAR(200) NOT NULL,
    attribute_bytes    BYTEA NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)
        REFERENCES spring_session (primary_id) ON DELETE CASCADE
);
