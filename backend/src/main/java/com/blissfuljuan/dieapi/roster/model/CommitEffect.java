package com.blissfuljuan.dieapi.roster.model;

/** What committing a resolved conflict row does. */
public enum CommitEffect {
    /** Enroll (or reinstate) the already registered student the row matched. */
    ENROLL_EXISTING,
    /** Leave the row out of the class. */
    SKIP
}
