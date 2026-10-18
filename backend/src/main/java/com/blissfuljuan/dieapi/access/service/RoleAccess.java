package com.blissfuljuan.dieapi.access.service;

import com.blissfuljuan.dieapi.access.model.ClassRole;
import com.blissfuljuan.dieapi.access.model.ProjectRole;
import com.blissfuljuan.dieapi.access.model.SqlScope;
import com.blissfuljuan.dieapi.account.model.UserRole;

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

    /** Restricts class listings (alias {@code c}). */
    SqlScope visibleClasses(UUID userId);

    /** Restricts project listings (alias {@code p}). */
    SqlScope visibleProjects(UUID userId);
}
