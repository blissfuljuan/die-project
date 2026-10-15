package com.blissfuljuan.die.academic.view;

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
    boolean studentsMayChangeTeams,
    int enrolledCount,
    long version,
    List<InstructorRef> instructors) {

    public record InstructorRef(UUID id, String firstName, String lastName) {}

    public ClassView withInstructors(List<InstructorRef> list) {
        return new ClassView(id, academicYear, semester, subjectCode, subjectName, section, status,
            teamFormation, teamMinSize, teamMaxSize, studentsMayChangeTeams, enrolledCount, version, list);
    }
}
