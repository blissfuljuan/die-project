package com.blissfuljuan.dieapi.access.repository;

import com.blissfuljuan.dieapi.access.model.SqlScope;
import com.blissfuljuan.dieapi.project.model.TeamRole;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * All SQL behind authorization decisions: existence and membership lookups and the list-scoping JOIN
 * fragments. It makes no decisions itself; {@code access.service} turns these facts into roles.
 */
@Repository
public class AccessRepository {

    private static final SqlScope EVERYTHING = new SqlScope("", Map.of());

    private final JdbcClient jdbc;

    public AccessRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- facts ----

    public boolean classExists(UUID classId) {
        return exists("SELECT 1 FROM academic_class WHERE id = :c", classId, null);
    }

    public boolean isClassInstructor(UUID classId, UUID userId) {
        return exists("SELECT 1 FROM class_instructor WHERE class_id = :c AND user_id = :u", classId, userId);
    }

    public boolean isEnrolled(UUID classId, UUID userId) {
        return exists("SELECT 1 FROM enrollment WHERE class_id = :c AND student_id = :u AND status = 'ENROLLED'",
            classId, userId);
    }

    public boolean projectExists(UUID projectId) {
        return exists("SELECT 1 FROM project WHERE id = :c", projectId, null);
    }

    /** True if the user is assigned as instructor to the class the project belongs to. */
    public boolean isInstructorOfProjectClass(UUID projectId, UUID userId) {
        return exists("""
            SELECT 1 FROM project p JOIN class_instructor ci ON ci.class_id = p.class_id
            WHERE p.id = :c AND ci.user_id = :u
            """, projectId, userId);
    }

    /** The user's current team role in the project, if they are a current member. */
    public Optional<TeamRole> currentTeamRole(UUID projectId, UUID userId) {
        return jdbc.sql("SELECT role FROM project_member WHERE project_id = :c AND user_id = :u AND left_at IS NULL")
            .param("c", projectId).param("u", userId).query(String.class).optional().map(TeamRole::valueOf);
    }

    // ---- list scopes (class listings use alias c, project listings alias p) ----

    public SqlScope allRows() {
        return EVERYTHING;
    }

    public SqlScope classesOfInstructor(UUID userId) {
        return new SqlScope(
            "JOIN class_instructor scope_ci ON scope_ci.class_id = c.id AND scope_ci.user_id = :scopeUser",
            Map.of("scopeUser", userId));
    }

    public SqlScope classesOfStudent(UUID userId) {
        return new SqlScope(
            "JOIN enrollment scope_e ON scope_e.class_id = c.id AND scope_e.student_id = :scopeUser "
                + "AND scope_e.status = 'ENROLLED'",
            Map.of("scopeUser", userId));
    }

    public SqlScope projectsOfInstructor(UUID userId) {
        return new SqlScope(
            "JOIN class_instructor scope_ci ON scope_ci.class_id = p.class_id AND scope_ci.user_id = :scopeUser",
            Map.of("scopeUser", userId));
    }

    public SqlScope projectsOfStudent(UUID userId) {
        return new SqlScope(
            "JOIN project_member scope_pm ON scope_pm.project_id = p.id AND scope_pm.user_id = :scopeUser "
                + "AND scope_pm.left_at IS NULL",
            Map.of("scopeUser", userId));
    }

    private boolean exists(String sql, UUID id, UUID userId) {
        var spec = jdbc.sql(sql).param("c", id);
        if (userId != null) {
            spec = spec.param("u", userId);
        }
        return spec.query(Integer.class).optional().isPresent();
    }
}
