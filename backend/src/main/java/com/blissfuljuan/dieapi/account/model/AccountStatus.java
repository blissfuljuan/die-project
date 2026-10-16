package com.blissfuljuan.dieapi.account.model;

import com.blissfuljuan.dieapi.common.util.Enums;

/** Account lifecycle status (persisted by name in app_user.status). */
public enum AccountStatus {
    PENDING_ACTIVATION, ACTIVE, DEACTIVATED;

    /** Exact lookup by name, or null. */
    public static AccountStatus parse(String text) {
        return Enums.parse(AccountStatus.class, text);
    }
}
