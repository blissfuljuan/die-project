package com.blissfuljuan.dieapi.project.service.creation;

import com.blissfuljuan.dieapi.access.model.ProjectRole;
import com.blissfuljuan.dieapi.account.model.UserRole;
import com.blissfuljuan.dieapi.common.exception.ApiException;
import com.blissfuljuan.dieapi.project.model.ProjectCreation;
import com.blissfuljuan.dieapi.project.model.ProjectKind;
import com.blissfuljuan.dieapi.project.model.TeamRole;
import com.blissfuljuan.dieapi.project.repository.ProjectRepository;
import com.blissfuljuan.dieapi.project.service.ProjectValidation;
import com.blissfuljuan.dieapi.project.service.TeamMembership;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** Staff assemble a team from named students (size limits apply unless an exception is requested). */
@Component
public class StaffTeamCreation implements ProjectCreationStrategy {

    private final ProjectRepository repo;
    private final TeamMembership team;
    private final ProjectValidation validation;

    public StaffTeamCreation(ProjectRepository repo, TeamMembership team, ProjectValidation validation) {
        this.repo = repo;
        this.team = team;
        this.validation = validation;
    }

    @Override
    public boolean supports(UserRole actor, ProjectKind kind) {
        return actor != UserRole.STUDENT && kind == ProjectKind.TEAM;
    }

    @Override
    public UUID create(ProjectCreation c) {
        List<UUID> members = ProjectValidation.distinct(c.request().memberIds());
        if (members.isEmpty()) {
            throw ApiException.badRequest("MEMBERS_REQUIRED", "memberIds must list at least one student");
        }
        ProjectValidation.checkTeamSize(c.cls(), members.size(), Boolean.TRUE.equals(c.request().allowSizeException()));
        UUID rep = c.request().representativeId() == null ? members.get(0) : c.request().representativeId();
        if (!members.contains(rep)) {
            throw ApiException.badRequest("INVALID_REPRESENTATIVE", "representativeId must be one of memberIds");
        }
        for (UUID m : members) validation.assertEnrolledAndFree(c.classId(), m, null);
        UUID projectId = repo.insert(c.classId(), c.kind(), c.title(), c.description(), c.teamName(), c.actor().getId());
        for (UUID m : members) {
            team.add(projectId, m, m.equals(rep) ? TeamRole.REPRESENTATIVE : TeamRole.MEMBER, c.actor().getId());
        }
        return projectId;
    }

    @Override
    public ProjectRole creatorRole() {
        return ProjectRole.STAFF;
    }
}
