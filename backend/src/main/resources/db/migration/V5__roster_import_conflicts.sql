-- V5: roster import conflict classification and commit bookkeeping.

ALTER TABLE roster_import_row
    ADD COLUMN conflict_kind TEXT
        CHECK (conflict_kind IN ('ID_NAME_MISMATCH', 'ID_REPEATED_WITH_DIFFERENT_NAME', 'NOT_A_STUDENT',
                                 'ACCOUNT_DEACTIVATED', 'EMAIL_TAKEN', 'EMAIL_REPEATED_IN_FILE'));

-- A row has a conflict kind exactly when its action is CONFLICT.
ALTER TABLE roster_import_row
    ADD CONSTRAINT chk_roster_row_conflict_kind CHECK ((action = 'CONFLICT') = (conflict_kind IS NOT NULL));

CREATE INDEX idx_roster_row_import_action ON roster_import_row (import_id, action);

ALTER TABLE roster_import ADD COLUMN committed_by UUID REFERENCES app_user (id);
