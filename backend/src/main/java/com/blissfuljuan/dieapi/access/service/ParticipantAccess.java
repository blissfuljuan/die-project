package com.blissfuljuan.dieapi.access.service;

import com.blissfuljuan.dieapi.access.model.ClassRole;
import com.blissfuljuan.dieapi.access.model.ProjectRole;
import com.blissfuljuan.dieapi.access.model.SqlScope;
import com.blissfuljuan.dieapi.access.repository.AccessRepository;
import com.blissfuljuan.dieapi.account.model.UserRole;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Students see the classes they are enrolled in and only the projects they are currently a member of. */
@Component
class ParticipantAccess implements RoleAccess {

    private final AccessRepository repo;

    ParticipantAccess(AccessRepository repo) {
        this.repo = repo;
    }

    @Override public UserRole role() { return UserRole.PARTICIPANT; }

    @Override public ClassRole classRole(UUID userId, UUID workspaceId) {
        return repo.isEnrolled(workspaceId, userId) ? ClassRole.PARTICIPANT : ClassRole.NONE;
    }

    @Override public ProjectRole projectRole(UUID userId, UUID projectId) {
        return repo.currentTeamRole(projectId, userId).orElse(ProjectRole.NONE);
    }

    @Override public SqlScope visibleClasses(UUID userId) { return repo.classesOfStudent(userId); }

    @Override public SqlScope visibleProjects(UUID userId) { return repo.projectsOfStudent(userId); }
}
