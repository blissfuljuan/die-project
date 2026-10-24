package com.blissfuljuan.dieapi.access.service;

import com.blissfuljuan.dieapi.access.model.ClassRole;
import com.blissfuljuan.dieapi.access.model.ProjectRole;
import com.blissfuljuan.dieapi.access.model.SqlScope;
import com.blissfuljuan.dieapi.account.model.UserRole;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Strategy: how one account role relates to classes and projects. One implementation per {@link UserRole},
 * collected by {@link RoleAccessRegistry}; adding a role means adding a class, not editing a switch.
 */
interface RoleAccess {

    UserRole role();

    /** What this user is to the class (the class is known to exist). */
    ClassRole classRole(UUID userId, UUID workspaceId);

    /** What this user is to the project (the project is known to exist). */
    ProjectRole projectRole(UUID userId, UUID projectId);

    /**
     * What this user is to each of several projects of ONE workspace (all known to exist). Implementations answer in one
     * query; this default asks one by one and exists only so a new role works before it is optimised.
     */
    default Map<UUID, ProjectRole> projectRoles(UUID userId, UUID workspaceId, Collection<UUID> projectIds) {
        Map<UUID, ProjectRole> out = new HashMap<>();
        for (UUID id : projectIds) {
            out.put(id, projectRole(userId, id));
        }
        return out;
    }

    /** Restricts class listings (alias {@code c}). */
    SqlScope visibleClasses(UUID userId);

    /** Restricts project listings (alias {@code p}). */
    SqlScope visibleProjects(UUID userId);
}
