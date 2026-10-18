package com.blissfuljuan.dieapi.access.service;

import com.blissfuljuan.dieapi.access.model.ClassRole;
import com.blissfuljuan.dieapi.access.model.SqlScope;
import com.blissfuljuan.dieapi.access.repository.AccessRepository;
import com.blissfuljuan.dieapi.auth.model.AppUserDetails;
import com.blissfuljuan.dieapi.common.exception.ApiException;
import com.blissfuljuan.dieapi.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Who may see or manage a class. A class the caller cannot see answers 404 exactly like a missing one. */
@Component
class ClassAccess {

    private final AccessRepository repo;
    private final RoleAccessRegistry roles;

    ClassAccess(AccessRepository repo, RoleAccessRegistry roles) {
        this.repo = repo;
        this.roles = roles;
    }

    ClassRole classRole(AppUserDetails user, UUID workspaceId) {
        return roles.forRole(user.role()).classRole(user.getId(), workspaceId);
    }

    /** Caller may read the class (staff of the class, admins, enrolled students). Otherwise 404. */
    ClassRole requireView(AppUserDetails user, UUID workspaceId) {
        if (!repo.classExists(workspaceId)) {
            throw notFound();
        }
        ClassRole role = classRole(user, workspaceId);
        if (role == ClassRole.NONE) {
            throw notFound();
        }
        return role;
    }

    /** Caller may change the class, its coordinators and its roster: admins and coordinators assigned to it. */
    ClassRole requireManage(AppUserDetails user, UUID workspaceId) {
        ClassRole role = requireView(user, workspaceId);
        if (!role.canManage()) {
            throw AccessPolicy.accessDenied();
        }
        return role;
    }

    /** Content access: view plus a role that may read content. Administrators get the 404 outsiders get. */
    ClassRole requireContent(AppUserDetails user, UUID workspaceId) {
        ClassRole role = requireView(user, workspaceId);
        if (!role.canAccessContent()) {
            throw notFound();
        }
        return role;
    }

    SqlScope visibleClasses(AppUserDetails user) {
        return roles.forRole(user.role()).visibleClasses(user.getId());
    }

    static ApiException notFound() {
        return ApiException.notFound(ErrorCode.CLASS_NOT_FOUND, "Workspace not found");
    }
}
