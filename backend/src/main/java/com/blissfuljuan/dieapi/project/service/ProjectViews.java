package com.blissfuljuan.dieapi.project.service;

import com.blissfuljuan.dieapi.academic.model.LockedClass;
import com.blissfuljuan.dieapi.academic.repository.ClassRepository;
import com.blissfuljuan.dieapi.access.model.ProjectRole;
import com.blissfuljuan.dieapi.access.service.AccessPolicy;
import com.blissfuljuan.dieapi.project.model.MemberRow;
import com.blissfuljuan.dieapi.project.model.ProjectKind;
import com.blissfuljuan.dieapi.project.model.ProjectRow;
import com.blissfuljuan.dieapi.project.repository.ProjectRepository;
import com.blissfuljuan.dieapi.project.view.InvitationView;
import com.blissfuljuan.dieapi.project.view.MemberView;
import com.blissfuljuan.dieapi.project.view.ProjectView;
import com.blissfuljuan.dieapi.project.view.TeamPolicy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** Builds the role-dependent ProjectView (staff see student numbers, editors see pending invitations). */
@Component
public class ProjectViews {

    private final ProjectRepository repo;
    private final ClassRepository classes;

    public ProjectViews(ProjectRepository repo, ClassRepository classes) {
        this.repo = repo;
        this.classes = classes;
    }

    public ProjectView viewOf(UUID projectId, ProjectRole role) {
        ProjectRow p = repo.find(projectId).orElseThrow(AccessPolicy::projectNotFound);
        var cls = classes.peek(p.classId()).orElseThrow(AccessPolicy::classNotFound);
        return build(p, role, cls, repo.currentMembers(projectId));
    }

    public ProjectView build(ProjectRow p, ProjectRole role, LockedClass cls, List<MemberRow> members) {
        boolean staff = role.isStaff();
        List<MemberView> memberViews = members.stream().map(m -> new MemberView(m.userId(), m.firstName(), m.lastName(),
            staff ? m.studentNumber() : null, m.role().name(), m.joinedAt())).toList();
        boolean meetsMin = p.kind() == ProjectKind.INDIVIDUAL ? members.size() == 1 : members.size() >= cls.teamMinSize();
        List<InvitationView> pending = role.canEdit() && p.kind() == ProjectKind.TEAM ? repo.pendingInvitations(p.id()) : List.of();
        return new ProjectView(p.id(), p.classId(), p.kind().name(), p.title(), p.description(), p.teamName(), p.status().name(),
            p.version(), p.createdAt(), role.name(), memberViews,
            new TeamPolicy(cls.teamFormation().name(), cls.teamMinSize(), cls.teamMaxSize(), cls.studentsMayChangeTeams()),
            meetsMin, pending);
    }
}
