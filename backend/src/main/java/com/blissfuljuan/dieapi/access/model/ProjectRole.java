package com.blissfuljuan.dieapi.access.model;

/**
 * What the caller is to a given project. STAFF = admin or instructor assigned to the project's class.
 * Students are MEMBER/REPRESENTATIVE only while their membership is current; classmates who are not
 * on the team are NONE (the project does not exist for them).
 */
public enum ProjectRole {
    NONE, MEMBER, REPRESENTATIVE, STAFF;

    public boolean isStaff() {
        return this == STAFF;
    }

    /** Staff and the team representative may edit project details and manage invitations. */
    public boolean canEdit() {
        return this == STAFF || this == REPRESENTATIVE;
    }
}
