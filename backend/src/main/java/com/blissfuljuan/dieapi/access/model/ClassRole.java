package com.blissfuljuan.dieapi.access.model;

/** What the caller is to a given workspace. Ordered by capability. */
public enum ClassRole {
    NONE, PARTICIPANT, COORDINATOR, ADMIN;

    public boolean canManage() {
        return this == COORDINATOR || this == ADMIN;
    }

    /** Workspace content (requirements, workflows, templates, evaluations): administrators have none. */
    public boolean canAccessContent() {
        return this == PARTICIPANT || this == COORDINATOR;
    }
}
