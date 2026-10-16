package com.blissfuljuan.dieapi.project.model;

import com.blissfuljuan.dieapi.common.util.Enums;

/** A student's role in a project team (persisted by name in project_member.role). */
public enum TeamRole {
    MEMBER, REPRESENTATIVE;

    /** Exact lookup by name, or null. */
    public static TeamRole parse(String text) {
        return Enums.parse(TeamRole.class, text);
    }
}
