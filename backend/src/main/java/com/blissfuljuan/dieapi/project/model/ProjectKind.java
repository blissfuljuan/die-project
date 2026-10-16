package com.blissfuljuan.dieapi.project.model;

import com.blissfuljuan.dieapi.common.util.Enums;

/** Individual or team project (persisted by name in project.kind). */
public enum ProjectKind {
    INDIVIDUAL, TEAM;

    /** Exact lookup by name, or null. */
    public static ProjectKind parse(String text) {
        return Enums.parse(ProjectKind.class, text);
    }
}
