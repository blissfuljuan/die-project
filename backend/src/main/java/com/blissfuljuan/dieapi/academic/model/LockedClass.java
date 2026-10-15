package com.blissfuljuan.dieapi.academic.model;

import java.util.UUID;

/** Class row read with FOR UPDATE (the row lock serializes enrollment and team changes inside the class). */
public record LockedClass(UUID id, String status, long version, String academicYear, String semester,
                          String subjectCode, String subjectName, String section, String teamFormation,
                          int teamMinSize, int teamMaxSize, boolean studentsMayChangeTeams) {}
