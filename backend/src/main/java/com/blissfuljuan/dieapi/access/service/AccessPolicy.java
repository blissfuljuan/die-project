package com.blissfuljuan.dieapi.access.service;

import com.blissfuljuan.dieapi.access.model.ClassRole;
import com.blissfuljuan.dieapi.access.model.ProjectRole;
import com.blissfuljuan.dieapi.access.model.SqlScope;
import com.blissfuljuan.dieapi.access.model.WorkspaceCapability;
import com.blissfuljuan.dieapi.auth.model.AppUserDetails;
import com.blissfuljuan.dieapi.common.exception.ApiException;
import com.blissfuljuan.dieapi.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The single place that decides who may touch which academic resource. Controllers and services ask
 * the policy; they never re-implement membership checks. A facade over {@link ClassAccess} and
 * {@link ProjectAccess}, which resolve roles through one {@link RoleAccess} strategy per account role;
 * all SQL lives in {@code AccessRepository}.
 *
 * Conventions:
 *  - A resource the caller cannot see answers 404 exactly like a resource that does not exist, so
 *    existence never leaks.
 *  - A caller who can see the resource but lacks the right to change it gets 403.
 *  - List queries are scoped in SQL via {@link #visibleClasses} / {@link #visibleProjects}, never
 *    filtered after the fact.
 *
 * Reviewer/approver and storage-ownership checks are added as collaborators here as those resources arrive.
 */
@Component
public class AccessPolicy {

    private final ClassAccess classes;
    private final ProjectAccess projects;
    private final WorkspaceCapabilityAccess capabilities;

    AccessPolicy(ClassAccess classes, ProjectAccess projects, WorkspaceCapabilityAccess capabilities) {
        this.classes = classes;
        this.projects = projects;
        this.capabilities = capabilities;
    }

    public ClassRole classRole(AppUserDetails user, UUID workspaceId) {
        return classes.classRole(user, workspaceId);
    }

    /** Caller may read the class (staff of the class, admins, enrolled students). Otherwise 404. */
    public ClassRole requireClassView(AppUserDetails user, UUID workspaceId) {
        return classes.requireView(user, workspaceId);
    }

    /** Caller may change the class, its coordinators and its roster: admins and coordinators assigned to it. */
    public ClassRole requireClassManage(AppUserDetails user, UUID workspaceId) {
        return classes.requireManage(user, workspaceId);
    }

    /**
     * Caller may perform the operation guarded by {@code capability} in the workspace. Outsiders: identical 404;
     * participants: 403; coordinators: 403 unless they hold the capability (the Owner holds all); administrators pass
     * only for administrative capabilities (they never pass a content-related capability: 404 like a content gate).
     * Replaces {@link #requireClassManage} for every sensitive coordinator operation.
     */
    public ClassRole requireWorkspaceCapability(AppUserDetails user, UUID workspaceId, WorkspaceCapability capability) {
        return capabilities.requireCapability(user, workspaceId, capability);
    }

    /** The Owner of the workspace or an administrator (owner recovery); other coordinators get 403, outsiders 404. */
    public ClassRole requireWorkspaceOwner(AppUserDetails user, UUID workspaceId) {
        return capabilities.requireOwner(user, workspaceId);
    }

    /** Backend-derived capabilities of the caller per workspace (empty for participants; administrators: administrative only). */
    public Map<UUID, Set<WorkspaceCapability>> capabilitiesOf(AppUserDetails user, Collection<UUID> workspaceIds) {
        return capabilities.capabilitiesOf(user, workspaceIds);
    }

    /** Restricts class listings: admins see all, coordinators their classes, students their enrolled classes. */
    public SqlScope visibleClasses(AppUserDetails user) {
        return classes.visibleClasses(user);
    }

    public ProjectRole projectRole(AppUserDetails user, UUID projectId) {
        return projects.projectRole(user, projectId);
    }

    /** The caller's role in each of several projects of one workspace, computed with a constant number of queries. */
    public Map<UUID, ProjectRole> projectRoles(AppUserDetails user, UUID workspaceId, Collection<UUID> projectIds) {
        return projects.projectRoles(user, workspaceId, projectIds);
    }

    /** Caller may read the project (staff of its class, current team members). Otherwise 404. */
    public ProjectRole requireProjectView(AppUserDetails user, UUID projectId) {
        return projects.requireView(user, projectId);
    }

    /** Staff or the team representative; a plain member can see the project but gets 403. */
    public ProjectRole requireProjectEdit(AppUserDetails user, UUID projectId) {
        return projects.requireEdit(user, projectId);
    }

    /** Admins and coordinators of the project's class only. */
    public ProjectRole requireProjectStaff(AppUserDetails user, UUID projectId) {
        return projects.requireStaff(user, projectId);
    }

    /**
     * Caller may read the CONTENT of the workspace (requirements, workflows, templates, evaluations). Administrators
     * are refused with the same 404 an outsider gets: administrator authority never grants content access.
     */
    public ClassRole requireClassContent(AppUserDetails user, UUID workspaceId) {
        return classes.requireContent(user, workspaceId);
    }

    /**
     * Caller may read the CONTENT of the project (documents, submissions, evaluations, reviews, approvals,
     * publications). Administrators and non-members get the same 404 as for a missing project. Further
     * restrictions (published-only for participants, reviewer assignment) are added by the owning module.
     */
    public ProjectRole requireProjectContent(AppUserDetails user, UUID projectId) {
        return projects.requireContent(user, projectId);
    }

    /** Restricts project listings (alias {@code p}): admins all, coordinators their classes', students their own teams. */
    public SqlScope visibleProjects(AppUserDetails user) {
        return projects.visibleProjects(user);
    }

    public static ApiException classNotFound() {
        return ClassAccess.notFound();
    }

    public static ApiException projectNotFound() {
        return ProjectAccess.notFound();
    }

    static ApiException accessDenied() {
        return ApiException.forbidden(ErrorCode.ACCESS_DENIED, "You do not have permission to perform this action.");
    }
}
