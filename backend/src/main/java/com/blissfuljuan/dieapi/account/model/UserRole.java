package com.blissfuljuan.dieapi.account.model;

import com.blissfuljuan.dieapi.common.util.Enums;

/** Account role (persisted by name in app_user.role). */
public enum UserRole {
    PARTICIPANT, COORDINATOR, ADMIN;

    /** Exact lookup by name, or null. */
    public static UserRole parse(String text) {
        return Enums.parse(UserRole.class, text);
    }
}
