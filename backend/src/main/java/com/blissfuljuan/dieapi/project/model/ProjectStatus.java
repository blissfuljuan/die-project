package com.blissfuljuan.dieapi.project.model;

import com.blissfuljuan.dieapi.common.util.Enums;

/** Project lifecycle (persisted by name in project.status). Archived projects are read-only. */
public enum ProjectStatus {
    ACTIVE, ARCHIVED;

    /** Exact lookup by name, or null. */
    public static ProjectStatus parse(String text) {
        return Enums.parse(ProjectStatus.class, text);
    }
}
