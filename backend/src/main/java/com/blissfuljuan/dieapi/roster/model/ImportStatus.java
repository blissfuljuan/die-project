package com.blissfuljuan.dieapi.roster.model;

import com.blissfuljuan.dieapi.common.util.Enums;

/** Lifecycle of a roster import (persisted by name in roster_import.status). */
public enum ImportStatus {
    PREVIEW, COMMITTED, CANCELLED;

    /** Exact lookup by name, or null. */
    public static ImportStatus parse(String text) {
        return Enums.parse(ImportStatus.class, text);
    }
}
