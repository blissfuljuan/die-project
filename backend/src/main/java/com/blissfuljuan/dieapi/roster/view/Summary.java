package com.blissfuljuan.dieapi.roster.view;

/** Counts by row outcome; unresolvedConflicts must be 0 before commit. */
public record Summary(int total, int create, int match, int skipDuplicate, int conflict, int invalid,
                      int unresolvedConflicts) {}
