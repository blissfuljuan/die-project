package com.blissfuljuan.dieapi.access.service;

import com.blissfuljuan.dieapi.access.model.ClassRole;
import com.blissfuljuan.dieapi.access.model.ProjectRole;
import com.blissfuljuan.dieapi.access.model.SqlScope;
import com.blissfuljuan.dieapi.access.repository.AccessRepository;
import com.blissfuljuan.dieapi.account.model.UserRole;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Instructors manage the classes they are assigned to and the projects in them. */
@Component
class InstructorAccess implements RoleAccess {

    private final AccessRepository repo;

    InstructorAccess(AccessRepository repo) {
        this.repo = repo;
    }

    @Override public UserRole role() { return UserRole.INSTRUCTOR; }

    @Override public ClassRole classRole(UUID userId, UUID classId) {
        return repo.isClassInstructor(classId, userId) ? ClassRole.INSTRUCTOR : ClassRole.NONE;
    }

    @Override public ProjectRole projectRole(UUID userId, UUID projectId) {
        return repo.isInstructorOfProjectClass(projectId, userId) ? ProjectRole.STAFF : ProjectRole.NONE;
    }

    @Override public SqlScope visibleClasses(UUID userId) { return repo.classesOfInstructor(userId); }

    @Override public SqlScope visibleProjects(UUID userId) { return repo.projectsOfInstructor(userId); }
}
