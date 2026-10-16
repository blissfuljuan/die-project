package com.blissfuljuan.dieapi.academic.model;

import com.blissfuljuan.dieapi.common.util.Enums;

/** Enrollment state (persisted by name in enrollment.status). */
public enum EnrollmentStatus {
    ENROLLED, REMOVED;

    /** Exact lookup by name, or null. */
    public static EnrollmentStatus parse(String text) {
        return Enums.parse(EnrollmentStatus.class, text);
    }
}
