package com.blissfuljuan.dieapi.access.model;

/** What the caller is to a given class. Ordered by capability. */
public enum ClassRole {
    NONE, STUDENT, INSTRUCTOR, ADMIN;

    public boolean canManage() {
        return this == INSTRUCTOR || this == ADMIN;
    }
}
