package com.blissfuljuan.dieapi.access.service;

import com.blissfuljuan.dieapi.access.model.ClassRole;
import com.blissfuljuan.dieapi.access.model.ProjectRole;
import com.blissfuljuan.dieapi.access.model.SqlScope;
import com.blissfuljuan.dieapi.access.repository.AccessRepository;
import com.blissfuljuan.dieapi.account.model.UserRole;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Instructors manage the classes they are assigned to and the projects in them. */
@Component
class CoordinatorAccess implements RoleAccess {

    private final AccessRepository repo;

    CoordinatorAccess(AccessRepository repo) {
        this.repo = repo;
    }

    @Override public UserRole role() { return UserRole.COORDINATOR; }

    @Override public ClassRole classRole(UUID userId, UUID workspaceId) {
        return repo.isClassInstructor(workspaceId, userId) ? ClassRole.COORDINATOR : ClassRole.NONE;
    }

    @Override public ProjectRole projectRole(UUID userId, UUID projectId) {
        return repo.isInstructorOfProjectClass(projectId, userId) ? ProjectRole.STAFF : ProjectRole.NONE;
    }

    /** Being assigned to the workspace is what makes someone staff of every project in it: one check, not one per project. */
    @Override public Map<UUID, ProjectRole> projectRoles(UUID userId, UUID workspaceId, Collection<UUID> projectIds) {
        ProjectRole role = repo.isClassInstructor(workspaceId, userId) ? ProjectRole.STAFF : ProjectRole.NONE;
        return projectIds.stream().collect(Collectors.toMap(Function.identity(), id -> role));
    }

    @Override public SqlScope visibleClasses(UUID userId) { return repo.classesOfInstructor(userId); }

    @Override public SqlScope visibleProjects(UUID userId) { return repo.projectsOfInstructor(userId); }
}
