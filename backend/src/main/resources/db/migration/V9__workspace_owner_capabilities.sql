-- V9: single designated Owner per Project Workspace and per-coordinator resource capabilities (brief v1.0 P1-VS03).
-- Internal table names keep the pre-ADR-0002 vocabulary (academic_class = workspace, class_instructor = assigned coordinator).

-- 1. Capabilities of an assigned coordinator. The Owner implicitly holds every capability (enforced in the application;
--    the stored array of the Owner is informational). Newly added coordinators default to roster + project administration.
ALTER TABLE class_instructor
    ADD COLUMN capabilities TEXT[] NOT NULL DEFAULT ARRAY['MANAGE_ROSTER', 'MANAGE_PROJECTS']::TEXT[];

-- Coordinators that already exist keep everything they could do before this migration (settings, roster, projects).
UPDATE class_instructor SET capabilities = ARRAY['MANAGE_SETTINGS', 'MANAGE_ROSTER', 'MANAGE_PROJECTS']::TEXT[];

ALTER TABLE class_instructor
    ADD CONSTRAINT ck_class_instructor_capabilities CHECK (capabilities <@ ARRAY[
        'MANAGE_SETTINGS', 'MANAGE_ROSTER', 'MANAGE_PROJECTS', 'MANAGE_COORDINATORS',
        'CONFIGURE_REQUIREMENTS', 'CONFIGURE_WORKFLOW', 'MANAGE_TEMPLATES', 'MANAGE_STORAGE',
        'INITIATE_EVALUATION', 'REVIEW', 'APPROVE', 'PUBLISH']::TEXT[]);

-- 2. Exactly one Owner per workspace. Backfill: the earliest-assigned coordinator, preferring accounts that are not
--    deactivated. Fails loudly if a workspace without any coordinator exists (the application never allowed that).
ALTER TABLE academic_class ADD COLUMN owner_id UUID;

UPDATE academic_class c
SET owner_id = (
    SELECT ci.user_id
    FROM class_instructor ci JOIN app_user u ON u.id = ci.user_id
    WHERE ci.class_id = c.id
    ORDER BY (u.status = 'DEACTIVATED'), ci.assigned_at, ci.user_id
    LIMIT 1);

ALTER TABLE academic_class ALTER COLUMN owner_id SET NOT NULL;

-- The Owner must be an assigned coordinator of the workspace, so the Owner cannot be unassigned while owning it.
-- DEFERRABLE INITIALLY DEFERRED: a workspace row and its first coordinator row can be inserted in either order within
-- one transaction, and an ownership transfer may reorder rows; the constraint is checked at commit (the DB backstop
-- against Owner-less workspaces under concurrent changes).
ALTER TABLE academic_class
    ADD CONSTRAINT fk_class_owner_is_coordinator FOREIGN KEY (id, owner_id)
        REFERENCES class_instructor (class_id, user_id) DEFERRABLE INITIALLY DEFERRED;

CREATE INDEX idx_class_owner ON academic_class (owner_id);

-- die_app privileges are table-level (V4) and unchanged: DML only, no new objects need grants.
