package com.blissfuljuan.dieapi.academic.model;

import com.blissfuljuan.dieapi.common.util.Enums;

/** Class lifecycle (persisted by name in academic_class.status). Archived classes are read-only. */
public enum ClassStatus {
    ACTIVE, ARCHIVED;

    /** Exact lookup by name, or null. */
    public static ClassStatus parse(String text) {
        return Enums.parse(ClassStatus.class, text);
    }
}
