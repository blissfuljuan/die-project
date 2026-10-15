-- V6: account ownership + case-insensitive student numbers + generic roster conflict kind.

-- Who created the account (admin via the API, or the instructor whose roster import created it).
-- Instructors may only enroll a never-activated student they created or already teach (see EnrollmentRules).
ALTER TABLE app_user ADD COLUMN created_by UUID REFERENCES app_user (id);

-- Student numbers are unique ignoring case ("ab1" and "AB1" are the same student).
DROP INDEX uq_app_user_student_number;
CREATE UNIQUE INDEX uq_app_user_student_number ON app_user (lower(student_number)) WHERE student_number IS NOT NULL;

-- ID_UNAVAILABLE: the Student ID cannot be imported by this caller; deliberately says nothing about why
-- (foreign account under another name, deactivated, not a student, or an account the caller may not claim).
ALTER TABLE roster_import_row DROP CONSTRAINT roster_import_row_conflict_kind_check;
ALTER TABLE roster_import_row
    ADD CONSTRAINT roster_import_row_conflict_kind_check
        CHECK (conflict_kind IN ('ID_NAME_MISMATCH', 'ID_REPEATED_WITH_DIFFERENT_NAME', 'ID_UNAVAILABLE',
                                 'NOT_A_STUDENT', 'ACCOUNT_DEACTIVATED', 'EMAIL_TAKEN', 'EMAIL_REPEATED_IN_FILE'));
