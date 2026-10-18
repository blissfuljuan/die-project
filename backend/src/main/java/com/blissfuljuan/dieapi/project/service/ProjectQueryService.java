package com.blissfuljuan.dieapi.project.service;

import com.blissfuljuan.dieapi.academic.model.TeamFormation;
import com.blissfuljuan.dieapi.academic.repository.ClassRepository;
import com.blissfuljuan.dieapi.access.model.ProjectRole;
import com.blissfuljuan.dieapi.access.service.AccessPolicy;
import com.blissfuljuan.dieapi.account.model.UserRole;
import com.blissfuljuan.dieapi.auth.model.AppUserDetails;
import com.blissfuljuan.dieapi.common.exception.ApiException;
import com.blissfuljuan.dieapi.common.exception.ErrorCode;
import com.blissfuljuan.dieapi.project.model.MemberRow;
import com.blissfuljuan.dieapi.project.model.ProjectRow;
import com.blissfuljuan.dieapi.project.repository.ProjectRepository;
import com.blissfuljuan.dieapi.project.view.EligibleParticipant;
import com.blissfuljuan.dieapi.project.view.HistoryEntry;
import com.blissfuljuan.dieapi.project.view.MyInvitation;
import com.blissfuljuan.dieapi.project.view.ProjectView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read side of projects. Access: AccessPolicy decides first (outsiders and classmates who are not on the team
 * get the same 404 as for a missing project).
 */
@Service
public class ProjectQueryService {

    private final ProjectRepository repo;
    private final ClassRepository classes;
    private final AccessPolicy policy;
    private final ProjectViews views;

    public ProjectQueryService(ProjectRepository repo, ClassRepository classes, AccessPolicy policy, ProjectViews views) {
        this.repo = repo;
        this.classes = classes;
        this.policy = policy;
        this.views = views;
    }

    @Transactional(readOnly = true)
    public List<ProjectView> list(AppUserDetails user, UUID workspaceId) {
        policy.requireClassView(user, workspaceId);
        var cls = classes.peek(workspaceId).orElseThrow(AccessPolicy::classNotFound);
        List<ProjectRow> rows = repo.list(workspaceId, policy.visibleProjects(user));
        Map<UUID, List<MemberRow>> members = repo.currentMembersOf(rows.stream().map(ProjectRow::id).toList());
        List<ProjectView> out = new ArrayList<>();
        for (ProjectRow p : rows) {
            ProjectRole role = policy.projectRole(user, p.id());
            out.add(views.build(p, role, cls, members.getOrDefault(p.id(), List.of())));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public ProjectView get(AppUserDetails user, UUID projectId) {
        ProjectRole role = policy.requireProjectView(user, projectId);
        return views.viewOf(projectId, role);
    }

    @Transactional(readOnly = true)
    public List<HistoryEntry> history(AppUserDetails user, UUID projectId) {
        policy.requireProjectStaff(user, projectId);
        return repo.history(projectId);
    }

    /** Classmates a student can invite. Only in classes where students form their own teams. */
    @Transactional(readOnly = true)
    public List<EligibleParticipant> eligibleParticipants(AppUserDetails user, UUID workspaceId) {
        policy.requireClassView(user, workspaceId);
        var cls = classes.peek(workspaceId).orElseThrow(AccessPolicy::classNotFound);
        if (user.role() == UserRole.PARTICIPANT && cls.teamFormation() != TeamFormation.PARTICIPANT_FORMED) {
            throw ApiException.forbidden(ErrorCode.TEAMS_ASSIGNED_BY_COORDINATOR, "Teams are assigned by your coordinator in this workspace");
        }
        return repo.eligibleParticipants(workspaceId, user.getId());
    }

    @Transactional(readOnly = true)
    public List<MyInvitation> myInvitations(AppUserDetails user) {
        return user.role() == UserRole.PARTICIPANT ? repo.pendingFor(user.getId()) : List.of();
    }
}
