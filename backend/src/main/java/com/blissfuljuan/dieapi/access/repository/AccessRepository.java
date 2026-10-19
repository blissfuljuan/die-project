package com.blissfuljuan.dieapi.access.repository;

import com.blissfuljuan.dieapi.access.model.CoordinatorGrant;
import com.blissfuljuan.dieapi.access.model.SqlScope;
import com.blissfuljuan.dieapi.access.model.ProjectRole;
import com.blissfuljuan.dieapi.access.model.WorkspaceCapability;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    public boolean classExists(UUID workspaceId) {
        return exists("SELECT 1 FROM academic_class WHERE id = :c", workspaceId, null);
    }

    public boolean isClassInstructor(UUID workspaceId, UUID userId) {
        return exists("SELECT 1 FROM class_instructor WHERE class_id = :c AND user_id = :u", workspaceId, userId);
    }

    public boolean isEnrolled(UUID workspaceId, UUID userId) {
        return exists("SELECT 1 FROM enrollment WHERE class_id = :c AND student_id = :u AND status = 'ENROLLED'",
            workspaceId, userId);
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

    /**
     * The user's current team role in the project as an access role (MEMBER or REPRESENTATIVE), if they are a
     * current member. Mapped from the stored role name here so that access does not depend on the project module.
     */
    public Optional<ProjectRole> currentTeamRole(UUID projectId, UUID userId) {
        return jdbc.sql("SELECT role FROM project_member WHERE project_id = :c AND user_id = :u AND left_at IS NULL")
            .param("c", projectId).param("u", userId).query(String.class).optional().map(AccessRepository::teamRoleToProjectRole);
    }

    private static ProjectRole teamRoleToProjectRole(String stored) {
        return switch (stored) {
            case "REPRESENTATIVE" -> ProjectRole.REPRESENTATIVE;
            case "MEMBER" -> ProjectRole.MEMBER;
            default -> throw new IllegalArgumentException("Unknown team role " + stored);
        };
    }

    /** The workspace a project belongs to. */
    public Optional<UUID> workspaceOfProject(UUID projectId) {
        return jdbc.sql("SELECT class_id FROM project WHERE id = :p").param("p", projectId).query(UUID.class).optional();
    }

    /** What the user holds as an assigned coordinator of the workspace (Owner flag + stored capabilities), if assigned. */
    public Optional<CoordinatorGrant> findGrant(UUID workspaceId, UUID userId) {
        return jdbc.sql("""
            SELECT (c.owner_id = ci.user_id) AS is_owner, ci.capabilities
            FROM class_instructor ci JOIN academic_class c ON c.id = ci.class_id
            WHERE ci.class_id = :c AND ci.user_id = :u
            """).param("c", workspaceId).param("u", userId).query((rs, n) -> grant(rs)).optional();
    }

    /** The user's grants in several workspaces at once (only those where the user is an assigned coordinator). */
    public Map<UUID, CoordinatorGrant> grantsOf(UUID userId, Collection<UUID> workspaceIds) {
        Map<UUID, CoordinatorGrant> result = new HashMap<>();
        if (workspaceIds.isEmpty()) {
            return result;
        }
        jdbc.sql("""
            SELECT ci.class_id, (c.owner_id = ci.user_id) AS is_owner, ci.capabilities
            FROM class_instructor ci JOIN academic_class c ON c.id = ci.class_id
            WHERE ci.user_id = :u AND ci.class_id IN (:ids)
            """).param("u", userId).param("ids", workspaceIds)
            .query((rs, n) -> {
                result.put(rs.getObject("class_id", UUID.class), grant(rs));
                return null;
            }).list();
        return result;
    }

    private static CoordinatorGrant grant(ResultSet rs) throws SQLException {
        Set<WorkspaceCapability> stored = EnumSet.noneOf(WorkspaceCapability.class);
        for (String name : Arrays.asList((String[]) rs.getArray("capabilities").getArray())) {
            WorkspaceCapability capability = WorkspaceCapability.parse(name);
            if (capability != null) {
                stored.add(capability);
            }
        }
        return new CoordinatorGrant(rs.getBoolean("is_owner"), stored);
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
