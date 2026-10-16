package com.blissfuljuan.dieapi.academic.model;

import java.util.UUID;

/** Class row read with FOR UPDATE (the row lock serializes enrollment and team changes inside the class). */
public record LockedClass(UUID id, ClassStatus status, long version, String academicYear, String semester,
                          String subjectCode, String subjectName, String section, TeamFormation teamFormation,
                          int teamMinSize, int teamMaxSize, boolean studentsMayChangeTeams) {}
