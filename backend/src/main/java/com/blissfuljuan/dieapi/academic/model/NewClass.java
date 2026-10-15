package com.blissfuljuan.dieapi.academic.model;

import java.util.UUID;

/** Values for inserting or updating a class. */
public record NewClass(String academicYear, String semester, String subjectCode, String subjectName,
                       String section, String teamFormation, int teamMinSize, int teamMaxSize,
                       boolean studentsMayChangeTeams, UUID createdBy) {}
