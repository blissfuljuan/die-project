package io.die.evaluator.project;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** API and persistence shapes for projects, teams and invitations. */
public final class ProjectModels {

    private ProjectModels() {}

    // ---- API views ----

    public record TeamPolicy(String formation, int minSize, int maxSize, boolean studentsMayChangeTeams) {}

    /** studentNumber is only filled for staff; students see classmates' names but not their numbers. */
    public record MemberView(UUID userId, String firstName, String lastName, String studentNumber, String role,
                             OffsetDateTime joinedAt) {}

    public record InvitationView(UUID id, UUID studentId, String firstName, String lastName, String status,
                                 OffsetDateTime expiresAt, OffsetDateTime createdAt) {}

    public record ProjectView(UUID id, UUID classId, String kind, String title, String description, String teamName,
                              String status, long version, OffsetDateTime createdAt, String myRole,
                              List<MemberView> members, TeamPolicy teamPolicy, boolean meetsMinSize,
                              List<InvitationView> pendingInvitations) {}

    public record HistoryEntry(UUID userId, String firstName, String lastName, String role, OffsetDateTime from,
                               OffsetDateTime until, UUID changedBy) {}

    public record MyInvitation(UUID id, UUID projectId, UUID classId, String subjectCode, String projectTitle,
                               String teamName, String invitedBy, OffsetDateTime expiresAt) {}

    public record Classmate(UUID id, String firstName, String lastName) {}

    // ---- persistence rows ----

    public record ProjectRow(UUID id, UUID classId, String kind, String title, String description, String teamName,
                             String status, long version, OffsetDateTime createdAt) {}

    /** joinedAt = first time the user joined this project (stable across role changes). */
    public record MemberRow(UUID userId, String firstName, String lastName, String studentNumber, String role,
                            OffsetDateTime joinedAt) {}

    public record InvitationRow(UUID id, UUID projectId, UUID invitedUserId, UUID invitedBy, String status,
                                OffsetDateTime expiresAt) {}
}
