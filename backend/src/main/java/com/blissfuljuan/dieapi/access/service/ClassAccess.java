package com.blissfuljuan.dieapi.access.service;

import com.blissfuljuan.dieapi.access.model.ClassRole;
import com.blissfuljuan.dieapi.access.model.SqlScope;
import com.blissfuljuan.dieapi.access.repository.AccessRepository;
import com.blissfuljuan.dieapi.auth.model.AppUserDetails;
import com.blissfuljuan.dieapi.common.exception.ApiException;
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

    ClassRole classRole(AppUserDetails user, UUID classId) {
        return roles.forRole(user.role()).classRole(user.getId(), classId);
    }

    /** Caller may read the class (staff of the class, admins, enrolled students). Otherwise 404. */
    ClassRole requireView(AppUserDetails user, UUID classId) {
        if (!repo.classExists(classId)) {
            throw notFound();
        }
        ClassRole role = classRole(user, classId);
        if (role == ClassRole.NONE) {
            throw notFound();
        }
        return role;
    }

    /** Caller may change the class, its instructors and its roster: admins and instructors assigned to it. */
    ClassRole requireManage(AppUserDetails user, UUID classId) {
        ClassRole role = requireView(user, classId);
        if (!role.canManage()) {
            throw AccessPolicy.accessDenied();
        }
        return role;
    }

    SqlScope visibleClasses(AppUserDetails user) {
        return roles.forRole(user.role()).visibleClasses(user.getId());
    }

    static ApiException notFound() {
        return ApiException.notFound("CLASS_NOT_FOUND", "Class not found");
    }
}
