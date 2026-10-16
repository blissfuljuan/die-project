package com.blissfuljuan.dieapi.project.service;

import com.blissfuljuan.dieapi.access.model.ProjectRole;
import com.blissfuljuan.dieapi.access.service.AccessPolicy;
import com.blissfuljuan.dieapi.audit.model.AuditAction;
import com.blissfuljuan.dieapi.audit.model.AuditEvent;
import com.blissfuljuan.dieapi.audit.model.AuditResource;
import org.springframework.context.ApplicationEventPublisher;
import com.blissfuljuan.dieapi.auth.model.AppUserDetails;
import com.blissfuljuan.dieapi.common.exception.ApiException;
import com.blissfuljuan.dieapi.project.model.MemberRow;
import com.blissfuljuan.dieapi.project.model.MembershipPlan;
import com.blissfuljuan.dieapi.project.model.ProjectKind;
import com.blissfuljuan.dieapi.project.model.SetMembers;
import com.blissfuljuan.dieapi.project.model.TeamRole;
import com.blissfuljuan.dieapi.project.model.rule.MembershipReconciler;
import com.blissfuljuan.dieapi.project.repository.ProjectRepository;
import com.blissfuljuan.dieapi.project.service.ProjectMutation.Requires;
import com.blissfuljuan.dieapi.project.view.ProjectView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.blissfuljuan.dieapi.project.service.ProjectValidation.checkTeamSize;
import static com.blissfuljuan.dieapi.project.service.ProjectValidation.distinct;

/** Membership of an existing project: staff replace members, hand over the representative role, students leave. */
@Service
public class ProjectMembershipService {

    private final ProjectRepository repo;
    private final AccessPolicy policy;
    private final TeamMembership team;
    private final ProjectMutation mutation;
    private final ProjectValidation validation;
    private final ProjectViews views;
    private final ApplicationEventPublisher events;
    private final MembershipReconciler reconciler = new MembershipReconciler();

    public ProjectMembershipService(ProjectRepository repo, AccessPolicy policy, TeamMembership team,
                                    ProjectMutation mutation, ProjectValidation validation, ProjectViews views,
                                    ApplicationEventPublisher events) {
        this.repo = repo;
        this.policy = policy;
        this.team = team;
        this.mutation = mutation;
        this.validation = validation;
        this.views = views;
        this.events = events;
    }

    /** Staff: replace the member set / representative (assign, transfer, exceptions). */
    @Transactional
    public ProjectView setMembers(AppUserDetails user, UUID projectId, SetMembers req) {
        ProjectValidation.requireVersion(req.version());
        return mutation.run(projectId, Requires.ACTIVE_PROJECT, () -> policy.requireProjectStaff(user, projectId),
            req.version(), (locked, role) -> {
                UUID classId = locked.project().classId();
                boolean individual = locked.project().kind() == ProjectKind.INDIVIDUAL;
                boolean exception = Boolean.TRUE.equals(req.allowSizeException());

                List<UUID> desired = distinct(req.memberIds());
                if (individual) {
                    if (desired.size() != 1) {
                        throw ApiException.badRequest("INDIVIDUAL_PROJECT_ONE_STUDENT", "An individual project has exactly one student");
                    }
                } else {
                    if (desired.isEmpty()) {
                        throw ApiException.badRequest("MEMBERS_REQUIRED", "memberIds must list at least one student");
                    }
                    checkTeamSize(locked.cls(), desired.size(), exception);
                }
                for (UUID id : desired) validation.assertEnrolledAndFree(classId, id, projectId);

                Map<UUID, TeamRole> current = new LinkedHashMap<>();
                repo.currentMembers(projectId).forEach(m -> current.put(m.userId(), m.role()));
                UUID rep = individual ? null : reconciler.chooseRepresentative(req.representativeId(), current, desired);
                apply(reconciler.plan(current, desired, rep), projectId, classId, user.getId());
                repo.touch(projectId);

                Map<String, Object> details = new LinkedHashMap<>();
                details.put("before", current.keySet().stream().map(UUID::toString).toList());
                details.put("after", desired.stream().map(UUID::toString).toList());
                details.put("sizeException", exception);
                events.publishEvent(AuditEvent.of(user.getId(), AuditAction.PROJECT_MEMBERS_SET, AuditResource.PROJECT, projectId.toString(), details));
                return views.viewOf(projectId, ProjectRole.STAFF);
            });
    }

    private void apply(MembershipPlan plan, UUID projectId, UUID classId, UUID actor) {
        for (UUID id : plan.removals()) repo.endMember(projectId, id, actor);
        for (UUID id : plan.demotions()) team.changeRole(projectId, id, TeamRole.MEMBER, actor);
        for (UUID id : plan.promotions()) team.changeRole(projectId, id, TeamRole.REPRESENTATIVE, actor);
        for (UUID id : plan.additions()) {
            team.add(projectId, id, plan.roleOfAddition(id), actor);
            repo.cancelPendingForUserInClass(classId, id);
        }
    }

    /** Staff or the current representative: hand the representative role to another current member. */
    @Transactional
    public ProjectView setRepresentative(AppUserDetails user, UUID projectId, UUID studentId) {
        return mutation.run(projectId, Requires.ACTIVE_PROJECT, () -> policy.requireProjectEdit(user, projectId),
            null, (locked, role) -> {
                if (locked.project().kind() == ProjectKind.INDIVIDUAL) {
                    throw ApiException.conflict("INDIVIDUAL_PROJECT_NO_REPRESENTATIVE", "An individual project has no representative");
                }
                List<MemberRow> members = repo.currentMembers(projectId);
                if (members.stream().noneMatch(m -> m.userId().equals(studentId))) {
                    throw ApiException.badRequest("INVALID_REPRESENTATIVE", "The new representative must be a current member of the team");
                }
                MemberRow old = members.stream().filter(m -> m.role() == TeamRole.REPRESENTATIVE).findFirst().orElse(null);
                if (old == null || !old.userId().equals(studentId)) {
                    if (old != null) team.changeRole(projectId, old.userId(), TeamRole.MEMBER, user.getId());
                    team.changeRole(projectId, studentId, TeamRole.REPRESENTATIVE, user.getId());
                    repo.touch(projectId);
                    events.publishEvent(AuditEvent.of(user.getId(), AuditAction.PROJECT_REPRESENTATIVE_CHANGED, AuditResource.PROJECT, projectId.toString(),
                        Map.of("from", old == null ? "" : old.userId().toString(), "to", studentId.toString())));
                }
                return views.viewOf(projectId, role);
            });
    }

    /** A student leaves their team, if the class policy allows team changes. */
    @Transactional
    public void leave(AppUserDetails user, UUID projectId) {
        mutation.run(projectId, Requires.ACTIVE_PROJECT, () -> {
            ProjectRole role = policy.requireProjectView(user, projectId); // also re-checked under the locks: a concurrent removal must not produce a bogus "left" event
            if (role == ProjectRole.STAFF) {
                throw ApiException.forbidden("STAFF_MANAGE_MEMBERSHIP", "Staff manage membership with PUT /members");
            }
            return role;
        }, null, (locked, role) -> {
            if (locked.project().kind() != ProjectKind.TEAM) {
                throw ApiException.conflict("INDIVIDUAL_PROJECT_CANNOT_BE_LEFT", "Individual projects cannot be left; ask your instructor");
            }
            if (!locked.cls().studentsMayChangeTeams()) {
                throw ApiException.forbidden("TEAM_CHANGES_NOT_ALLOWED", "Your instructor does not allow students to change teams");
            }
            team.remove(projectId, user.getId(), user.getId(), "left team");
            events.publishEvent(AuditEvent.of(user.getId(), AuditAction.PROJECT_MEMBER_LEFT, AuditResource.PROJECT, projectId.toString(), Map.of()));
            return null;
        });
    }
}
