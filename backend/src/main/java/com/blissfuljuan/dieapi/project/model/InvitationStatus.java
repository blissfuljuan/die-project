package com.blissfuljuan.dieapi.project.model;

import com.blissfuljuan.dieapi.common.util.Enums;

/** Team invitation state (persisted by name in team_invitation.status). */
public enum InvitationStatus {
    PENDING, ACCEPTED, DECLINED, EXPIRED, CANCELLED;

    /** Exact lookup by name, or null. */
    public static InvitationStatus parse(String text) {
        return Enums.parse(InvitationStatus.class, text);
    }
}
