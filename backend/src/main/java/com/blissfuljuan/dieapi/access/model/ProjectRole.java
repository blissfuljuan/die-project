package com.blissfuljuan.dieapi.access.model;

/**
 * What the caller is to a given project. STAFF = coordinator assigned to the project's workspace; ADMIN =
 * administrator, who may administer membership but has NO access to project content (documents, evaluations,
 * reviews, approvals; brief v1.0 section 2, ADR 0002). Participants are MEMBER/REPRESENTATIVE only while
 * their membership is current; participants who are not on the team are NONE (the project does not exist for them).
 */
public enum ProjectRole {
    NONE, MEMBER, REPRESENTATIVE, STAFF, ADMIN;

    /** Coordinators and administrators administer the project (membership, archive). */
    public boolean isStaff() {
        return this == STAFF || this == ADMIN;
    }

    /** Staff and the team representative may edit project details and manage invitations. */
    public boolean canEdit() {
        return isStaff() || this == REPRESENTATIVE;
    }

    /**
     * Documents, submissions, evaluations, reviews, approvals and publications of the project. Never granted to
     * administrators. Every content endpoint added in P2/P3 must gate on this (via AccessPolicy.requireProjectContent).
     */
    public boolean canAccessContent() {
        return this == MEMBER || this == REPRESENTATIVE || this == STAFF;
    }
}
