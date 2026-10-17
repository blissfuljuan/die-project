package com.blissfuljuan.dieapi.project.service.creation;

import com.blissfuljuan.dieapi.access.model.ProjectRole;
import com.blissfuljuan.dieapi.account.model.UserRole;
import com.blissfuljuan.dieapi.common.exception.ApiException;
import com.blissfuljuan.dieapi.common.exception.ErrorCode;
import com.blissfuljuan.dieapi.project.model.ProjectCreation;
import com.blissfuljuan.dieapi.project.model.ProjectKind;
import com.blissfuljuan.dieapi.project.model.TeamRole;
import com.blissfuljuan.dieapi.project.repository.ProjectRepository;
import com.blissfuljuan.dieapi.project.service.ProjectValidation;
import com.blissfuljuan.dieapi.project.service.TeamMembership;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Staff create an individual project for one named student. */
@Component
public class StaffIndividualCreation implements ProjectCreationStrategy {

    private final ProjectRepository repo;
    private final TeamMembership team;
    private final ProjectValidation validation;

    public StaffIndividualCreation(ProjectRepository repo, TeamMembership team, ProjectValidation validation) {
        this.repo = repo;
        this.team = team;
        this.validation = validation;
    }

    @Override
    public boolean supports(UserRole actor, ProjectKind kind) {
        return actor != UserRole.STUDENT && kind == ProjectKind.INDIVIDUAL;
    }

    @Override
    public UUID create(ProjectCreation c) {
        UUID studentId = c.request().studentId();
        if (studentId == null) {
            throw ApiException.badRequest(ErrorCode.STUDENT_REQUIRED, "studentId is required for an individual project");
        }
        validation.assertEnrolledAndFree(c.classId(), studentId, null);
        UUID projectId = repo.insert(c.classId(), c.kind(), c.title(), c.description(), null, c.actor().getId());
        team.add(projectId, studentId, TeamRole.MEMBER, c.actor().getId());
        return projectId;
    }

    @Override
    public ProjectRole creatorRole() {
        return ProjectRole.STAFF;
    }
}
