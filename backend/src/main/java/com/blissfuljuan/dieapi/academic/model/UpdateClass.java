package com.blissfuljuan.dieapi.academic.model;

/** Command to patch a class; null fields keep their current value. */
public record UpdateClass(Long version, String academicYear, String semester, String subjectCode,
                          String subjectName, String section, String teamFormation, Integer teamMinSize,
                          Integer teamMaxSize, Boolean studentsMayChangeTeams) {}
