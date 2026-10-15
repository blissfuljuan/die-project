-- V3: academic classes, enrollment, roster imports, projects and teams.

CREATE TABLE academic_class (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    academic_year       TEXT NOT NULL,
    semester            TEXT NOT NULL,
    subject_code        TEXT NOT NULL,
    subject_name        TEXT NOT NULL,
    section             TEXT NOT NULL,
    status              TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    -- Team policy (instructor-defined)
    team_formation      TEXT NOT NULL DEFAULT 'INSTRUCTOR_ASSIGNED'
                        CHECK (team_formation IN ('STUDENT_FORMED', 'INSTRUCTOR_ASSIGNED')),
    team_min_size       INTEGER NOT NULL DEFAULT 1 CHECK (team_min_size >= 1),
    team_max_size       INTEGER NOT NULL DEFAULT 5,
    students_may_change_teams BOOLEAN NOT NULL DEFAULT FALSE,
    created_by          UUID REFERENCES app_user (id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_team_sizes CHECK (team_max_size >= team_min_size),
    CONSTRAINT uq_class_identity UNIQUE (academic_year, semester, subject_code, section)
);

CREATE TABLE class_instructor (
    class_id    UUID NOT NULL REFERENCES academic_class (id) ON DELETE CASCADE,
    user_id     UUID NOT NULL REFERENCES app_user (id),
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (class_id, user_id)
);
CREATE INDEX idx_class_instructor_user ON class_instructor (user_id);

CREATE TABLE enrollment (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    class_id    UUID NOT NULL REFERENCES academic_class (id) ON DELETE CASCADE,
    student_id  UUID NOT NULL REFERENCES app_user (id),
    program     TEXT,
    section     TEXT,
    status      TEXT NOT NULL DEFAULT 'ENROLLED' CHECK (status IN ('ENROLLED', 'REMOVED')),
    enrolled_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    removed_at  TIMESTAMPTZ,
    CONSTRAINT uq_enrollment UNIQUE (class_id, student_id)
);
CREATE INDEX idx_enrollment_student ON enrollment (student_id);

-- Roster import: parsed on upload (PREVIEW), applied on commit.
CREATE TABLE roster_import (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    class_id     UUID NOT NULL REFERENCES academic_class (id) ON DELETE CASCADE,
    uploaded_by  UUID NOT NULL REFERENCES app_user (id),
    file_name    TEXT NOT NULL,
    file_sha256  TEXT NOT NULL,
    status       TEXT NOT NULL DEFAULT 'PREVIEW' CHECK (status IN ('PREVIEW', 'COMMITTED', 'CANCELLED')),
    summary      JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    committed_at TIMESTAMPTZ
);
CREATE INDEX idx_roster_import_class ON roster_import (class_id);

CREATE TABLE roster_import_row (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    import_id     UUID NOT NULL REFERENCES roster_import (id) ON DELETE CASCADE,
    row_number    INTEGER NOT NULL,
    student_number TEXT,
    last_name     TEXT,
    first_name    TEXT,
    middle_name   TEXT,
    email         TEXT,
    program       TEXT,
    section       TEXT,
    action        TEXT NOT NULL CHECK (action IN ('CREATE', 'MATCH', 'SKIP_DUPLICATE', 'CONFLICT', 'INVALID')),
    resolution    TEXT CHECK (resolution IN ('USE_EXISTING', 'SKIP', 'CREATE_NEW')),
    message       TEXT,
    user_id       UUID REFERENCES app_user (id),
    CONSTRAINT uq_roster_row UNIQUE (import_id, row_number)
);

CREATE TABLE project (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    class_id    UUID NOT NULL REFERENCES academic_class (id) ON DELETE CASCADE,
    title       TEXT NOT NULL,
    description TEXT,
    kind        TEXT NOT NULL CHECK (kind IN ('INDIVIDUAL', 'TEAM')),
    team_name   TEXT,
    status      TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    -- Populated in P2 when workflows exist.
    workflow_version_id UUID,
    created_by  UUID REFERENCES app_user (id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    version     BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_project_class ON project (class_id);

-- Membership history: a row is "current" while left_at IS NULL.
CREATE TABLE project_member (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    user_id    UUID NOT NULL REFERENCES app_user (id),
    role       TEXT NOT NULL DEFAULT 'MEMBER' CHECK (role IN ('MEMBER', 'REPRESENTATIVE')),
    joined_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    left_at    TIMESTAMPTZ,
    changed_by UUID REFERENCES app_user (id)
);
CREATE UNIQUE INDEX uq_project_member_current ON project_member (project_id, user_id) WHERE left_at IS NULL;
CREATE UNIQUE INDEX uq_project_representative ON project_member (project_id)
    WHERE left_at IS NULL AND role = 'REPRESENTATIVE';
CREATE INDEX idx_project_member_user ON project_member (user_id) WHERE left_at IS NULL;

CREATE TABLE team_invitation (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id  UUID NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    invited_user_id UUID NOT NULL REFERENCES app_user (id),
    invited_by  UUID NOT NULL REFERENCES app_user (id),
    status      TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'EXPIRED', 'CANCELLED')),
    expires_at  TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    responded_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_team_invitation_pending ON team_invitation (project_id, invited_user_id) WHERE status = 'PENDING';
CREATE INDEX idx_team_invitation_user ON team_invitation (invited_user_id, status);
