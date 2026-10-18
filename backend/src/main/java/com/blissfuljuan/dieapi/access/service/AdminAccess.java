package com.blissfuljuan.dieapi.access.service;

import com.blissfuljuan.dieapi.access.model.ClassRole;
import com.blissfuljuan.dieapi.access.model.ProjectRole;
import com.blissfuljuan.dieapi.access.model.SqlScope;
import com.blissfuljuan.dieapi.access.repository.AccessRepository;
import com.blissfuljuan.dieapi.account.model.UserRole;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Administrators see and administer every workspace and project, but have no access to their content (ADR 0002). */
@Component
class AdminAccess implements RoleAccess {

    private final AccessRepository repo;

    AdminAccess(AccessRepository repo) {
        this.repo = repo;
    }

    @Override public UserRole role() { return UserRole.ADMIN; }

    @Override public ClassRole classRole(UUID userId, UUID workspaceId) { return ClassRole.ADMIN; }

    @Override public ProjectRole projectRole(UUID userId, UUID projectId) { return ProjectRole.ADMIN; }

    @Override public SqlScope visibleClasses(UUID userId) { return repo.allRows(); }

    @Override public SqlScope visibleProjects(UUID userId) { return repo.allRows(); }
}
