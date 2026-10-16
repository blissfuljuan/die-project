package com.blissfuljuan.dieapi.roster.model;

import com.blissfuljuan.dieapi.common.util.Enums;

/** What the analysis decided for one roster row (persisted by name in roster_import_row.action). */
public enum RowOutcome {
    CREATE, MATCH, SKIP_DUPLICATE, CONFLICT, INVALID;

    /** Exact lookup by name, or null. */
    public static RowOutcome parse(String text) {
        return Enums.parse(RowOutcome.class, text);
    }
}
