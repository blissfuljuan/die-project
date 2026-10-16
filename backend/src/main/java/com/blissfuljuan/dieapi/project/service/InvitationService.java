package com.blissfuljuan.dieapi.project.service;

import com.blissfuljuan.dieapi.academic.model.EnrollmentStatus;
import com.blissfuljuan.dieapi.academic.model.TeamFormation;
import com.blissfuljuan.dieapi.academic.repository.ClassRepository;
import com.blissfuljuan.dieapi.access.model.ProjectRole;
import com.blissfuljuan.dieapi.access.service.AccessPolicy;
import com.blissfuljuan.dieapi.account.model.AccountStatus;
import com.blissfuljuan.dieapi.account.model.UserRole;
import com.blissfuljuan.dieapi.audit.model.AuditAction;
import com.blissfuljuan.dieapi.audit.model.AuditEvent;
import org.springframework.context.ApplicationEventPublisher;
import com.blissfuljuan.dieapi.auth.model.AppUserDetails;
import com.blissfuljuan.dieapi.common.exception.ApiException;
import com.blissfuljuan.dieapi.project.model.InvitationRow;
import com.blissfuljuan.dieapi.project.model.InvitationStatus;
import com.blissfuljuan.dieapi.project.model.LockedProject;
import com.blissfuljuan.dieapi.project.model.ProjectKind;
import com.blissfuljuan.dieapi.project.model.ProjectStatus;
import com.blissfuljuan.dieapi.project.model.TeamRole;
import com.blissfuljuan.dieapi.project.repository.ProjectRepository;
import com.blissfuljuan.dieapi.project.service.ProjectMutation.Requires;
import com.blissfuljuan.dieapi.project.view.InvitationView;
import com.blissfuljuan.dieapi.project.view.ProjectView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Team invitations. Lock order is always class -> project -> invitation (every staff path locks the class
 * first and then touches invitation rows).
 */
@Service
public class InvitationService {

    private final ProjectRepository repo;
    private final ClassRepository classes;
    private final AccessPolicy policy;
    private final TeamMembership team;
    private final ProjectMutation mutation;
    private final ProjectViews views;
    private final ApplicationEventPublisher events;
    private final int invitationDays;

    public InvitationService(ProjectRepository repo, ClassRepository classes, AccessPolicy policy, TeamMembership team,
                             ProjectMutation mutation, ProjectViews views, ApplicationEventPublisher events,
                             @Value("${die.team.invitation-days:7}") int invitationDays) {
        this.repo = repo;
        this.classes = classes;
        this.policy = policy;
        this.team = team;
        this.mutation = mutation;
        this.views = views;
        this.events = events;
        this.invitationDays = invitationDays;
    }

    @Transactional
    public InvitationView invite(AppUserDetails user, UUID projectId, UUID studentId) {
        return mutation.run(projectId, Requires.ACTIVE_PROJECT, () -> policy.requireProjectEdit(user, projectId),
            null, (locked, role) -> {
                if (locked.project().kind() != ProjectKind.TEAM) {
                    throw ApiException.conflict("INVITATIONS_TEAM_ONLY", "Only team projects have invitations");
                }
                if (role == ProjectRole.REPRESENTATIVE && locked.cls().teamFormation() != TeamFormation.STUDENT_FORMED) {
                    throw ApiException.forbidden("TEAMS_ASSIGNED_BY_INSTRUCTOR", "Teams are assigned by your instructor in this class");
                }
                UUID classId = locked.project().classId();
                if (studentId.equals(user.getId())) throw ApiException.badRequest("CANNOT_INVITE_SELF", "You cannot invite yourself");
                var student = classes.findStudentById(studentId)
                    .filter(s -> s.role() == UserRole.STUDENT && s.status() != AccountStatus.DEACTIVATED);
                var enrollment = classes.enrollmentStatus(classId, studentId);
                if (student.isEmpty() || enrollment.isEmpty() || enrollment.get() != EnrollmentStatus.ENROLLED) {
                    // Same answer for unknown ids and students of other classes: no roster probing.
                    throw ApiException.notFound("CLASSMATE_NOT_FOUND", "Classmate not found");
                }
                if (repo.activeProjectOf(classId, studentId).isPresent()) {
                    throw ApiException.conflict("STUDENT_ALREADY_IN_PROJECT", "The student already belongs to a project in this class");
                }
                int taken = repo.currentMembers(projectId).size() + repo.livePendingCount(projectId);
                if (taken >= locked.cls().teamMaxSize()) {
                    throw ApiException.conflict("TEAM_FULL", "The team is full (members plus pending invitations)");
                }
                UUID id = repo.insertInvitation(projectId, studentId, user.getId(), invitationDays);
                events.publishEvent(AuditEvent.of(user.getId(), AuditAction.TEAM_INVITATION_SENT, "project", projectId.toString(),
                    Map.of("invitationId", id.toString(), "studentId", studentId.toString())));
                return repo.pendingInvitations(projectId).stream().filter(i -> i.id().equals(id)).findFirst().orElseThrow();
            });
    }

    @Transactional
    public void cancelInvitation(AppUserDetails user, UUID projectId, UUID invitationId) {
        mutation.run(projectId, Requires.ACTIVE_PROJECT, () -> policy.requireProjectEdit(user, projectId),
            null, (locked, role) -> {
                var inv = repo.findInvitation(invitationId, true).filter(i -> i.projectId().equals(projectId))
                    .orElseThrow(() -> ApiException.notFound("INVITATION_NOT_FOUND", "Invitation not found"));
                if (inv.status() != InvitationStatus.PENDING) {
                    throw ApiException.conflict("INVITATION_NOT_PENDING", "The invitation is no longer pending");
                }
                repo.setInvitationStatus(invitationId, InvitationStatus.CANCELLED);
                events.publishEvent(AuditEvent.of(user.getId(), AuditAction.TEAM_INVITATION_CANCELLED, "project", projectId.toString(),
                    Map.of("invitationId", invitationId.toString())));
                return null;
            });
    }

    @Transactional
    public ProjectView accept(AppUserDetails user, UUID invitationId) {
        // Find the invitation without a lock, take the class and project locks, and only then lock and re-read
        // the invitation. Locking the invitation first could deadlock against the staff paths.
        UUID projectId = ownInvitation(user, invitationId, false).projectId();
        LockedProject locked = mutation.lockForInvitee(projectId);
        UUID classId = locked.project().classId();
        var inv = ownInvitation(user, invitationId, true);
        requireStillValid(inv);
        if (locked.project().status() != ProjectStatus.ACTIVE || locked.project().kind() != ProjectKind.TEAM
            || locked.cls().teamFormation() != TeamFormation.STUDENT_FORMED) {
            throw ApiException.conflict("TEAM_NOT_ACCEPTING_MEMBERS", "This team is no longer accepting members");
        }
        var enrollment = classes.enrollmentStatus(classId, user.getId());
        if (enrollment.isEmpty() || enrollment.get() != EnrollmentStatus.ENROLLED) {
            throw ApiException.conflict("NOT_ENROLLED", "You are no longer enrolled in this class");
        }
        if (repo.activeProjectOf(classId, user.getId()).isPresent()) {
            throw ApiException.conflict("ALREADY_IN_PROJECT", "You already belong to a project in this class");
        }
        if (repo.currentMembers(projectId).size() >= locked.cls().teamMaxSize()) {
            throw ApiException.conflict("TEAM_FULL", "The team is full");
        }
        team.add(projectId, user.getId(), TeamRole.MEMBER, user.getId());
        repo.setInvitationStatus(invitationId, InvitationStatus.ACCEPTED);
        repo.cancelPendingForUserInClass(classId, user.getId());
        repo.touch(projectId);
        events.publishEvent(AuditEvent.of(user.getId(), AuditAction.TEAM_INVITATION_ACCEPTED, "project", projectId.toString(),
            Map.of("invitationId", invitationId.toString())));
        return views.viewOf(projectId, ProjectRole.MEMBER);
    }

    @Transactional
    public void decline(AppUserDetails user, UUID invitationId) {
        var inv = ownInvitation(user, invitationId, true); // takes no other lock, so no ordering issue
        requireStillValid(inv);
        repo.setInvitationStatus(invitationId, InvitationStatus.DECLINED);
        events.publishEvent(AuditEvent.of(user.getId(), AuditAction.TEAM_INVITATION_DECLINED, "project", inv.projectId().toString(),
            Map.of("invitationId", invitationId.toString())));
    }

    /** The invitation must exist and be addressed to the caller; anyone else gets the same 404. */
    private InvitationRow ownInvitation(AppUserDetails user, UUID invitationId, boolean lock) {
        return repo.findInvitation(invitationId, lock).filter(i -> i.invitedUserId().equals(user.getId()))
            .orElseThrow(() -> ApiException.notFound("INVITATION_NOT_FOUND", "Invitation not found"));
    }

    private static void requireStillValid(InvitationRow inv) {
        if (inv.status() != InvitationStatus.PENDING || inv.expiresAt().isBefore(OffsetDateTime.now())) {
            throw ApiException.conflict("INVITATION_INVALID", "The invitation is no longer valid");
        }
    }
}
