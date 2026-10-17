package com.blissfuljuan.dieapi.access.service;

import com.blissfuljuan.dieapi.access.model.ProjectRole;
import com.blissfuljuan.dieapi.access.model.SqlScope;
import com.blissfuljuan.dieapi.access.repository.AccessRepository;
import com.blissfuljuan.dieapi.auth.model.AppUserDetails;
import com.blissfuljuan.dieapi.common.exception.ApiException;
import com.blissfuljuan.dieapi.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Who may see or change a project. Outsiders and classmates who are not on the team get the same 404 as for a missing project. */
@Component
class ProjectAccess {

    private final AccessRepository repo;
    private final RoleAccessRegistry roles;

    ProjectAccess(AccessRepository repo, RoleAccessRegistry roles) {
        this.repo = repo;
        this.roles = roles;
    }

    ProjectRole projectRole(AppUserDetails user, UUID projectId) {
        if (!repo.projectExists(projectId)) {
            return ProjectRole.NONE;
        }
        return roles.forRole(user.role()).projectRole(user.getId(), projectId);
    }

    /** Caller may read the project (staff of its class, current team members). Otherwise 404. */
    ProjectRole requireView(AppUserDetails user, UUID projectId) {
        ProjectRole role = projectRole(user, projectId);
        if (role == ProjectRole.NONE) {
            throw notFound();
        }
        return role;
    }

    /** Staff or the team representative; a plain member can see the project but gets 403. */
    ProjectRole requireEdit(AppUserDetails user, UUID projectId) {
        ProjectRole role = requireView(user, projectId);
        if (!role.canEdit()) {
            throw AccessPolicy.accessDenied();
        }
        return role;
    }

    /** Admins and instructors of the project's class only. */
    ProjectRole requireStaff(AppUserDetails user, UUID projectId) {
        ProjectRole role = requireView(user, projectId);
        if (!role.isStaff()) {
            throw AccessPolicy.accessDenied();
        }
        return role;
    }

    SqlScope visibleProjects(AppUserDetails user) {
        return roles.forRole(user.role()).visibleProjects(user.getId());
    }

    static ApiException notFound() {
        return ApiException.notFound(ErrorCode.PROJECT_NOT_FOUND, "Project not found");
    }
}
