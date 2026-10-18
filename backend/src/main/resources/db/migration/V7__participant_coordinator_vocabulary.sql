-- V7: brief v1.0 vocabulary (ADR 0002). Stored enum values only; table and column names are unchanged.
--   role STUDENT -> PARTICIPANT, INSTRUCTOR -> COORDINATOR
--   team_formation STUDENT_FORMED -> PARTICIPANT_FORMED, INSTRUCTOR_ASSIGNED -> COORDINATOR_ASSIGNED
--   roster conflict kind NOT_A_STUDENT -> NOT_A_PARTICIPANT
-- audit_log.action values are history and are deliberately NOT rewritten.

ALTER TABLE app_user DROP CONSTRAINT app_user_role_check;
ALTER TABLE app_user DROP CONSTRAINT chk_student_has_number;
UPDATE app_user SET role = 'PARTICIPANT' WHERE role = 'STUDENT';
UPDATE app_user SET role = 'COORDINATOR' WHERE role = 'INSTRUCTOR';
ALTER TABLE app_user ADD CONSTRAINT app_user_role_check CHECK (role IN ('PARTICIPANT', 'COORDINATOR', 'ADMIN'));
ALTER TABLE app_user ADD CONSTRAINT chk_participant_has_number
    CHECK (role <> 'PARTICIPANT' OR student_number IS NOT NULL);

ALTER TABLE academic_class DROP CONSTRAINT academic_class_team_formation_check;
ALTER TABLE academic_class ALTER COLUMN team_formation SET DEFAULT 'COORDINATOR_ASSIGNED';
UPDATE academic_class SET team_formation = 'PARTICIPANT_FORMED' WHERE team_formation = 'STUDENT_FORMED';
UPDATE academic_class SET team_formation = 'COORDINATOR_ASSIGNED' WHERE team_formation = 'INSTRUCTOR_ASSIGNED';
ALTER TABLE academic_class ADD CONSTRAINT academic_class_team_formation_check
    CHECK (team_formation IN ('PARTICIPANT_FORMED', 'COORDINATOR_ASSIGNED'));

ALTER TABLE roster_import_row DROP CONSTRAINT roster_import_row_conflict_kind_check;
UPDATE roster_import_row SET conflict_kind = 'NOT_A_PARTICIPANT' WHERE conflict_kind = 'NOT_A_STUDENT';
ALTER TABLE roster_import_row ADD CONSTRAINT roster_import_row_conflict_kind_check
    CHECK (conflict_kind IN ('ID_NAME_MISMATCH', 'ID_REPEATED_WITH_DIFFERENT_NAME', 'ID_UNAVAILABLE',
                             'NOT_A_PARTICIPANT', 'ACCOUNT_DEACTIVATED', 'EMAIL_TAKEN', 'EMAIL_REPEATED_IN_FILE'));
