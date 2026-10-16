package com.blissfuljuan.dieapi.access.service;

import com.blissfuljuan.dieapi.access.model.ClassRole;
import com.blissfuljuan.dieapi.access.model.ProjectRole;
import com.blissfuljuan.dieapi.access.model.SqlScope;
import com.blissfuljuan.dieapi.auth.model.AppUserDetails;
import com.blissfuljuan.dieapi.common.exception.ApiException;
import com.blissfuljuan.dieapi.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

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

    AccessPolicy(ClassAccess classes, ProjectAccess projects) {
        this.classes = classes;
        this.projects = projects;
    }

    public ClassRole classRole(AppUserDetails user, UUID classId) {
        return classes.classRole(user, classId);
    }

    /** Caller may read the class (staff of the class, admins, enrolled students). Otherwise 404. */
    public ClassRole requireClassView(AppUserDetails user, UUID classId) {
        return classes.requireView(user, classId);
    }

    /** Caller may change the class, its instructors and its roster: admins and instructors assigned to it. */
    public ClassRole requireClassManage(AppUserDetails user, UUID classId) {
        return classes.requireManage(user, classId);
    }

    /** Restricts class listings: admins see all, instructors their classes, students their enrolled classes. */
    public SqlScope visibleClasses(AppUserDetails user) {
        return classes.visibleClasses(user);
    }

    public ProjectRole projectRole(AppUserDetails user, UUID projectId) {
        return projects.projectRole(user, projectId);
    }

    /** Caller may read the project (staff of its class, current team members). Otherwise 404. */
    public ProjectRole requireProjectView(AppUserDetails user, UUID projectId) {
        return projects.requireView(user, projectId);
    }

    /** Staff or the team representative; a plain member can see the project but gets 403. */
    public ProjectRole requireProjectEdit(AppUserDetails user, UUID projectId) {
        return projects.requireEdit(user, projectId);
    }

    /** Admins and instructors of the project's class only. */
    public ProjectRole requireProjectStaff(AppUserDetails user, UUID projectId) {
        return projects.requireStaff(user, projectId);
    }

    /** Restricts project listings (alias {@code p}): admins all, instructors their classes', students their own teams. */
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
