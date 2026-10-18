package com.blissfuljuan.dieapi.academic.view;

import java.util.List;
import java.util.UUID;

public record ClassView(
    UUID id,
    String academicYear,
    String semester,
    String subjectCode,
    String subjectName,
    String section,
    String status,
    String teamFormation,
    int teamMinSize,
    int teamMaxSize,
    boolean participantsMayChangeTeams,
    int enrolledCount,
    long version,
    List<CoordinatorRef> coordinators) {

    public record CoordinatorRef(UUID id, String firstName, String lastName) {}

    public ClassView withCoordinators(List<CoordinatorRef> list) {
        return new ClassView(id, academicYear, semester, subjectCode, subjectName, section, status,
            teamFormation, teamMinSize, teamMaxSize, participantsMayChangeTeams, enrolledCount, version, list);
    }
}
