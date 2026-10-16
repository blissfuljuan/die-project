package com.blissfuljuan.dieapi.access.service;

import com.blissfuljuan.dieapi.access.model.ClassRole;
import com.blissfuljuan.dieapi.access.model.ProjectRole;
import com.blissfuljuan.dieapi.access.model.SqlScope;
import com.blissfuljuan.dieapi.access.repository.AccessRepository;
import com.blissfuljuan.dieapi.account.model.UserRole;
import com.blissfuljuan.dieapi.project.model.TeamRole;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Students see the classes they are enrolled in and only the projects they are currently a member of. */
@Component
class StudentAccess implements RoleAccess {

    private final AccessRepository repo;

    StudentAccess(AccessRepository repo) {
        this.repo = repo;
    }

    @Override public UserRole role() { return UserRole.STUDENT; }

    @Override public ClassRole classRole(UUID userId, UUID classId) {
        return repo.isEnrolled(classId, userId) ? ClassRole.STUDENT : ClassRole.NONE;
    }

    @Override public ProjectRole projectRole(UUID userId, UUID projectId) {
        return repo.currentTeamRole(projectId, userId)
            .map(r -> r == TeamRole.REPRESENTATIVE ? ProjectRole.REPRESENTATIVE : ProjectRole.MEMBER)
            .orElse(ProjectRole.NONE);
    }

    @Override public SqlScope visibleClasses(UUID userId) { return repo.classesOfStudent(userId); }

    @Override public SqlScope visibleProjects(UUID userId) { return repo.projectsOfStudent(userId); }
}
