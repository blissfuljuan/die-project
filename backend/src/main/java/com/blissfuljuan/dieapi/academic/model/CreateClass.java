package com.blissfuljuan.dieapi.academic.model;

import java.util.List;
import java.util.UUID;

/** Command to create a class; fields are validated by the service. */
public record CreateClass(String academicYear, String semester, String subjectCode, String subjectName,
                          String section, String teamFormation, Integer teamMinSize, Integer teamMaxSize,
                          Boolean studentsMayChangeTeams, List<UUID> instructorIds) {}
