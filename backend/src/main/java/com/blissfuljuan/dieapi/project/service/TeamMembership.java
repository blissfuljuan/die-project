package com.blissfuljuan.dieapi.project.service;

import com.blissfuljuan.dieapi.audit.model.AuditAction;
import com.blissfuljuan.dieapi.audit.model.AuditEvent;
import org.springframework.context.ApplicationEventPublisher;
import com.blissfuljuan.dieapi.project.model.MemberRow;
import com.blissfuljuan.dieapi.project.model.ProjectStatus;
import com.blissfuljuan.dieapi.project.model.TeamRole;
import com.blissfuljuan.dieapi.project.repository.ProjectRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Membership mutations with their invariants. Every call assumes the caller already passed
 * AccessPolicy and holds the class row lock (class first, then project), which serializes all
 * membership changes inside a class. History is append-only: leaving or changing role closes the
 * current project_member row (left_at) and role changes open a new one.
 */
@Component
public class TeamMembership {

    private final ProjectRepository repo;
    private final ApplicationEventPublisher events;

    public TeamMembership(ProjectRepository repo, ApplicationEventPublisher events) {
        this.repo = repo;
        this.events = events;
    }

    public void add(UUID projectId, UUID userId, TeamRole role, UUID actor) {
        repo.insertMember(projectId, userId, role, actor);
    }

    /** Closes the current row and opens one with the new role. */
    public void changeRole(UUID projectId, UUID userId, TeamRole newRole, UUID actor) {
        repo.endMember(projectId, userId, actor);
        repo.insertMember(projectId, userId, newRole, actor);
    }

    /**
     * Removes a member. If the representative leaves a team that still has members, the member who joined
     * first becomes representative; a project left with nobody is archived (its history is kept).
     */
    public void remove(UUID projectId, UUID userId, UUID actor, String reason) {
        List<MemberRow> before = repo.currentMembers(projectId);
        boolean wasRepresentative = before.stream().anyMatch(m -> m.userId().equals(userId) && m.role() == TeamRole.REPRESENTATIVE);
        repo.endMember(projectId, userId, actor);
        List<MemberRow> remaining = before.stream().filter(m -> !m.userId().equals(userId)).toList();

        if (remaining.isEmpty()) {
            repo.setStatus(projectId, ProjectStatus.ARCHIVED);
            repo.cancelAllPendingForProject(projectId);
            events.publishEvent(AuditEvent.of(actor, AuditAction.PROJECT_ARCHIVED_EMPTY, "project", projectId.toString(), Map.of("reason", reason)));
            return;
        }
        if (wasRepresentative) {
            MemberRow successor = remaining.get(0); // list is ordered by first join
            changeRole(projectId, successor.userId(), TeamRole.REPRESENTATIVE, actor);
            events.publishEvent(AuditEvent.of(actor, AuditAction.PROJECT_REPRESENTATIVE_SUCCEEDED, "project", projectId.toString(),
                Map.of("from", userId.toString(), "to", successor.userId().toString(), "reason", reason)));
        }
        repo.touch(projectId);
    }

    /**
     * A student who is no longer enrolled leaves every project of that class and loses pending invitations.
     * Called from ClassEnrollmentService.unenroll (class row already locked).
     */
    public void onStudentRemovedFromClass(UUID classId, UUID studentId, UUID actor) {
        repo.cancelPendingForUserInClass(classId, studentId);
        for (UUID projectId : repo.activeProjectIdsOf(classId, studentId)) {
            repo.lock(projectId);
            remove(projectId, studentId, actor, "removed from class");
        }
    }
}
