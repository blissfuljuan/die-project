package com.blissfuljuan.dieapi.roster.model;

import com.blissfuljuan.dieapi.common.util.Enums;

/**
 * Why a row needs an instructor decision (persisted by name in roster_import_row.conflict_kind).
 * The last four values are still accepted by the database but are no longer produced by the analysis.
 */
public enum ConflictKind {
    ID_NAME_MISMATCH, ID_REPEATED_WITH_DIFFERENT_NAME, ID_UNAVAILABLE,
    NOT_A_STUDENT, ACCOUNT_DEACTIVATED, EMAIL_TAKEN, EMAIL_REPEATED_IN_FILE;

    /** Exact lookup by name, or null. */
    public static ConflictKind parse(String text) {
        return Enums.parse(ConflictKind.class, text);
    }
}
