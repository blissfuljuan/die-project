package io.die.evaluator.access;

import io.die.evaluator.auth.AppUserDetails;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * The single place that decides who may touch which academic resource. Controllers and services ask
 * the policy; they never re-implement membership checks.
 *
 * Conventions:
 *  - A resource the caller cannot see answers 404 exactly like a resource that does not exist, so
 *    existence never leaks.
 *  - A caller who can see the resource but lacks the right to change it gets 403.
 *  - List queries are scoped in SQL via {@link #visibleClasses}, never filtered after the fact.
 *
 * Project/team/reviewer/approver checks are added here as those resources arrive.
 */
@Component
public class AccessPolicy {

    /** What the caller is to a given class. Ordered by capability. */
    public enum ClassRole {
        NONE, STUDENT, INSTRUCTOR, ADMIN;

        public boolean canManage() {
            return this == INSTRUCTOR || this == ADMIN;
        }
    }

    /**
     * What the caller is to a given project. STAFF = admin or instructor assigned to the project's class.
     * Students are MEMBER/REPRESENTATIVE only while their membership is current; classmates who are not
     * on the team are NONE (the project does not exist for them).
     */
    public enum ProjectRole {
        NONE, MEMBER, REPRESENTATIVE, STAFF;

        public boolean isStaff() {
            return this == STAFF;
        }

        /** Staff and the team representative may edit project details and manage invitations. */
        public boolean canEdit() {
            return this == STAFF || this == REPRESENTATIVE;
        }
    }

    /** SQL fragment restricting a query on {@code academic_class c} to what the caller may see. */
    public record SqlScope(String join, Map<String, Object> params) {}

    private final JdbcClient jdbc;

    public AccessPolicy(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public ClassRole classRole(AppUserDetails user, UUID classId) {
        return switch (user.getRole()) {
            case "ADMIN" -> ClassRole.ADMIN;
            case "INSTRUCTOR" -> exists("SELECT 1 FROM class_instructor WHERE class_id = :c AND user_id = :u",
                classId, user.getId()) ? ClassRole.INSTRUCTOR : ClassRole.NONE;
            case "STUDENT" -> exists("SELECT 1 FROM enrollment WHERE class_id = :c AND student_id = :u AND status = 'ENROLLED'",
                classId, user.getId()) ? ClassRole.STUDENT : ClassRole.NONE;
            default -> ClassRole.NONE;
        };
    }

    /** Caller may read the class (staff of the class, admins, enrolled students). Otherwise 404. */
    public ClassRole requireClassView(AppUserDetails user, UUID classId) {
        if (!exists("SELECT 1 FROM academic_class WHERE id = :c", classId, null)) {
            throw classNotFound();
        }
        ClassRole role = classRole(user, classId);
        if (role == ClassRole.NONE) {
            throw classNotFound();
        }
        return role;
    }

    /** Caller may change the class, its instructors and its roster: admins and instructors assigned to it. */
    public ClassRole requireClassManage(AppUserDetails user, UUID classId) {
        ClassRole role = requireClassView(user, classId);
        if (!role.canManage()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
        return role;
    }

    public ProjectRole projectRole(AppUserDetails user, UUID projectId) {
        if (!exists("SELECT 1 FROM project WHERE id = :c", projectId, null)) {
            return ProjectRole.NONE;
        }
        return switch (user.getRole()) {
            case "ADMIN" -> ProjectRole.STAFF;
            case "INSTRUCTOR" -> exists("""
                SELECT 1 FROM project p JOIN class_instructor ci ON ci.class_id = p.class_id
                WHERE p.id = :c AND ci.user_id = :u
                """, projectId, user.getId()) ? ProjectRole.STAFF : ProjectRole.NONE;
            case "STUDENT" -> jdbc.sql("SELECT role FROM project_member WHERE project_id = :c AND user_id = :u AND left_at IS NULL")
                .param("c", projectId).param("u", user.getId()).query(String.class).optional()
                .map(r -> "REPRESENTATIVE".equals(r) ? ProjectRole.REPRESENTATIVE : ProjectRole.MEMBER)
                .orElse(ProjectRole.NONE);
            default -> ProjectRole.NONE;
        };
    }

    /** Caller may read the project (staff of its class, current team members). Otherwise 404. */
    public ProjectRole requireProjectView(AppUserDetails user, UUID projectId) {
        ProjectRole role = projectRole(user, projectId);
        if (role == ProjectRole.NONE) {
            throw projectNotFound();
        }
        return role;
    }

    /** Staff or the team representative; a plain member can see the project but gets 403. */
    public ProjectRole requireProjectEdit(AppUserDetails user, UUID projectId) {
        ProjectRole role = requireProjectView(user, projectId);
        if (!role.canEdit()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
        return role;
    }

    /** Admins and instructors of the project's class only. */
    public ProjectRole requireProjectStaff(AppUserDetails user, UUID projectId) {
        ProjectRole role = requireProjectView(user, projectId);
        if (!role.isStaff()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
        return role;
    }

    /** Restricts project listings (alias {@code p}): admins all, instructors their classes', students their own teams. */
    public SqlScope visibleProjects(AppUserDetails user) {
        return switch (user.getRole()) {
            case "ADMIN" -> new SqlScope("", Map.of());
            case "INSTRUCTOR" -> new SqlScope(
                "JOIN class_instructor scope_ci ON scope_ci.class_id = p.class_id AND scope_ci.user_id = :scopeUser",
                Map.of("scopeUser", user.getId()));
            case "STUDENT" -> new SqlScope(
                "JOIN project_member scope_pm ON scope_pm.project_id = p.id AND scope_pm.user_id = :scopeUser "
                    + "AND scope_pm.left_at IS NULL",
                Map.of("scopeUser", user.getId()));
            default -> new SqlScope("JOIN (SELECT 1 AS none WHERE FALSE) scope_none ON FALSE", Map.of());
        };
    }

    public static ResponseStatusException projectNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
    }

    /** Restricts class listings: admins see all, instructors their classes, students their enrolled classes. */
    public SqlScope visibleClasses(AppUserDetails user) {
        return switch (user.getRole()) {
            case "ADMIN" -> new SqlScope("", Map.of());
            case "INSTRUCTOR" -> new SqlScope(
                "JOIN class_instructor scope_ci ON scope_ci.class_id = c.id AND scope_ci.user_id = :scopeUser",
                Map.of("scopeUser", user.getId()));
            case "STUDENT" -> new SqlScope(
                "JOIN enrollment scope_e ON scope_e.class_id = c.id AND scope_e.student_id = :scopeUser "
                    + "AND scope_e.status = 'ENROLLED'",
                Map.of("scopeUser", user.getId()));
            default -> new SqlScope("JOIN (SELECT 1 AS none WHERE FALSE) scope_none ON FALSE", Map.of());
        };
    }

    public static ResponseStatusException classNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Class not found");
    }

    private boolean exists(String sql, UUID classId, UUID userId) {
        var spec = jdbc.sql(sql).param("c", classId);
        if (userId != null) {
            spec = spec.param("u", userId);
        }
        return spec.query(Integer.class).optional().isPresent();
    }
}
