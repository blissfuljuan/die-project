package com.blissfuljuan.dieapi.academic.view;

import java.util.List;
import java.util.UUID;

/**
 * A workspace. {@code ownerId} is the single designated Owner (always one of the coordinators);
 * {@code myCapabilities} is derived by the backend for the caller (empty for participants; administrators get only
 * the administrative capabilities, never anything that implies content access).
 */
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
    UUID ownerId,
    List<String> myCapabilities,
    List<CoordinatorRef> coordinators) {

    public record CoordinatorRef(UUID id, String firstName, String lastName, boolean owner) {}

    public ClassView withCoordinators(List<CoordinatorRef> list) {
        return new ClassView(id, academicYear, semester, subjectCode, subjectName, section, status,
            teamFormation, teamMinSize, teamMaxSize, participantsMayChangeTeams, enrolledCount, version,
            ownerId, myCapabilities, list);
    }

    public ClassView withMyCapabilities(List<String> capabilities) {
        return new ClassView(id, academicYear, semester, subjectCode, subjectName, section, status,
            teamFormation, teamMinSize, teamMaxSize, participantsMayChangeTeams, enrolledCount, version,
            ownerId, capabilities, coordinators);
    }
}
