package com.blissfuljuan.dieapi.project.service.creation;

import com.blissfuljuan.dieapi.academic.model.TeamFormation;
import com.blissfuljuan.dieapi.access.model.ProjectRole;
import com.blissfuljuan.dieapi.account.model.UserRole;
import com.blissfuljuan.dieapi.common.exception.ApiException;
import com.blissfuljuan.dieapi.project.model.ProjectCreation;
import com.blissfuljuan.dieapi.project.model.ProjectKind;
import com.blissfuljuan.dieapi.project.model.TeamRole;
import com.blissfuljuan.dieapi.project.repository.ProjectRepository;
import com.blissfuljuan.dieapi.project.service.TeamMembership;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** A student starts a team in a class where students form their own teams and becomes its representative. */
@Component
public class StudentTeamCreation implements ProjectCreationStrategy {

    private final ProjectRepository repo;
    private final TeamMembership team;

    public StudentTeamCreation(ProjectRepository repo, TeamMembership team) {
        this.repo = repo;
        this.team = team;
    }

    @Override
    public boolean supports(UserRole actor, ProjectKind kind) {
        return actor == UserRole.STUDENT;
    }

    @Override
    public UUID create(ProjectCreation c) {
        if (c.kind() != ProjectKind.TEAM || c.cls().teamFormation() != TeamFormation.STUDENT_FORMED) {
            throw ApiException.forbidden("STUDENT_PROJECT_NOT_ALLOWED",
                "Students can only create teams, and only in classes where students form their own teams");
        }
        UUID studentId = c.actor().getId();
        if (repo.activeProjectOf(c.classId(), studentId).isPresent()) {
            throw ApiException.conflict("ALREADY_IN_PROJECT", "You already belong to a project in this class");
        }
        UUID projectId = repo.insert(c.classId(), c.kind(), c.title(), c.description(), c.teamName(), studentId);
        team.add(projectId, studentId, TeamRole.REPRESENTATIVE, studentId);
        return projectId;
    }

    @Override
    public ProjectRole creatorRole() {
        return ProjectRole.REPRESENTATIVE;
    }
}
