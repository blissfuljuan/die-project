package com.blissfuljuan.die.project.repository;

import com.blissfuljuan.die.access.model.SqlScope;
import com.blissfuljuan.die.project.model.InvitationRow;
import com.blissfuljuan.die.project.model.MemberRow;
import com.blissfuljuan.die.project.model.ProjectRow;
import com.blissfuljuan.die.project.view.Classmate;
import com.blissfuljuan.die.project.view.HistoryEntry;
import com.blissfuljuan.die.project.view.InvitationView;
import com.blissfuljuan.die.project.view.MyInvitation;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for projects, memberships and invitations. Authorization is the caller's job (AccessPolicy). */
@Repository
public class ProjectRepository {

    private static final String PROJECT_COLUMNS =
        "p.id, p.class_id, p.kind, p.title, p.description, p.team_name, p.status, p.version, p.created_at";

    private final JdbcClient jdbc;

    public ProjectRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static ProjectRow mapProject(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new ProjectRow(rs.getObject("id", UUID.class), rs.getObject("class_id", UUID.class),
            rs.getString("kind"), rs.getString("title"), rs.getString("description"), rs.getString("team_name"),
            rs.getString("status"), rs.getLong("version"), rs.getObject("created_at", OffsetDateTime.class));
    }

    // ---- projects ----

    public UUID insert(UUID classId, String kind, String title, String description, String teamName, UUID createdBy) {
        return jdbc.sql("""
                INSERT INTO project (class_id, title, description, kind, team_name, created_by)
                VALUES (:c, :t, :d, :k, :n, :by) RETURNING id
                """)
            .param("c", classId).param("t", title).param("d", description).param("k", kind)
            .param("n", teamName).param("by", createdBy).query(UUID.class).single();
    }

    public Optional<ProjectRow> find(UUID id) {
        return jdbc.sql("SELECT " + PROJECT_COLUMNS + " FROM project p WHERE p.id = :id")
            .param("id", id).query(ProjectRepository::mapProject).optional();
    }

    public Optional<ProjectRow> lock(UUID id) {
        return jdbc.sql("SELECT " + PROJECT_COLUMNS + " FROM project p WHERE p.id = :id FOR UPDATE")
            .param("id", id).query(ProjectRepository::mapProject).optional();
    }

    public Optional<UUID> classIdOf(UUID projectId) {
        return jdbc.sql("SELECT class_id FROM project WHERE id = :id").param("id", projectId)
            .query(UUID.class).optional();
    }

    public List<ProjectRow> list(UUID classId, SqlScope scope) {
        return jdbc.sql("SELECT " + PROJECT_COLUMNS + " FROM project p " + scope.join()
                + " WHERE p.class_id = :c ORDER BY p.created_at, p.id")
            .params(scope.params()).param("c", classId).query(ProjectRepository::mapProject).list();
    }

    public void updateDetails(UUID id, String title, String description, String teamName) {
        jdbc.sql("""
                UPDATE project SET title = :t, description = :d, team_name = :n, updated_at = now(), version = version + 1
                WHERE id = :id
                """)
            .param("id", id).param("t", title).param("d", description).param("n", teamName).update();
    }

    public void setStatus(UUID id, String status) {
        jdbc.sql("UPDATE project SET status = :s, updated_at = now(), version = version + 1 WHERE id = :id")
            .param("id", id).param("s", status).update();
    }

    /** Membership changes bump the version so concurrent edits based on older state are rejected. */
    public void touch(UUID id) {
        jdbc.sql("UPDATE project SET updated_at = now(), version = version + 1 WHERE id = :id").param("id", id).update();
    }

    /** Largest current team size among ACTIVE team projects of the class (0 if none). */
    public int maxActiveTeamSize(UUID classId) {
        return jdbc.sql("""
                SELECT COALESCE(max(n), 0) FROM (
                    SELECT count(*) AS n FROM project p JOIN project_member m ON m.project_id = p.id AND m.left_at IS NULL
                    WHERE p.class_id = :c AND p.kind = 'TEAM' AND p.status = 'ACTIVE' GROUP BY p.id) sizes
                """)
            .param("c", classId).query(Integer.class).single();
    }

    // ---- members ----

    private static final String MEMBER_SELECT = """
        SELECT m.project_id, u.id AS user_id, u.first_name, u.last_name, u.student_number, m.role,
               (SELECT min(m2.joined_at) FROM project_member m2 WHERE m2.project_id = m.project_id AND m2.user_id = m.user_id) AS first_joined
        FROM project_member m JOIN app_user u ON u.id = m.user_id
        WHERE m.left_at IS NULL
        """;

    public List<MemberRow> currentMembers(UUID projectId) {
        return jdbc.sql(MEMBER_SELECT + " AND m.project_id = :p ORDER BY first_joined, u.id")
            .param("p", projectId).query(ProjectRepository::mapMember).list();
    }

    public Map<UUID, List<MemberRow>> currentMembersOf(Collection<UUID> projectIds) {
        Map<UUID, List<MemberRow>> out = new HashMap<>();
        if (projectIds.isEmpty()) return out;
        jdbc.sql(MEMBER_SELECT + " AND m.project_id IN (:ids) ORDER BY first_joined, u.id")
            .param("ids", projectIds)
            .query((rs, n) -> {
                out.computeIfAbsent(rs.getObject("project_id", UUID.class), k -> new ArrayList<>()).add(mapMember(rs, n));
                return 0;
            }).list();
        return out;
    }

    private static MemberRow mapMember(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new MemberRow(rs.getObject("user_id", UUID.class), rs.getString("first_name"), rs.getString("last_name"),
            rs.getString("student_number"), rs.getString("role"), rs.getObject("first_joined", OffsetDateTime.class));
    }

    public void insertMember(UUID projectId, UUID userId, String role, UUID changedBy) {
        jdbc.sql("INSERT INTO project_member (project_id, user_id, role, changed_by) VALUES (:p, :u, :r, :by)")
            .param("p", projectId).param("u", userId).param("r", role).param("by", changedBy).update();
    }

    public int endMember(UUID projectId, UUID userId, UUID changedBy) {
        return jdbc.sql("""
                UPDATE project_member SET left_at = now(), changed_by = :by
                WHERE project_id = :p AND user_id = :u AND left_at IS NULL
                """)
            .param("p", projectId).param("u", userId).param("by", changedBy).update();
    }

    /** The ACTIVE project of the class the student currently belongs to, if any. */
    public Optional<UUID> activeProjectOf(UUID classId, UUID userId) {
        return jdbc.sql("""
                SELECT p.id FROM project p JOIN project_member m ON m.project_id = p.id AND m.left_at IS NULL
                WHERE p.class_id = :c AND p.status = 'ACTIVE' AND m.user_id = :u
                """)
            .param("c", classId).param("u", userId).query(UUID.class).optional();
    }

    /** All ACTIVE projects in the class where the student is a current member (normally 0 or 1). */
    public List<UUID> activeProjectIdsOf(UUID classId, UUID userId) {
        return jdbc.sql("""
                SELECT p.id FROM project p JOIN project_member m ON m.project_id = p.id AND m.left_at IS NULL
                WHERE p.class_id = :c AND p.status = 'ACTIVE' AND m.user_id = :u
                """)
            .param("c", classId).param("u", userId).query(UUID.class).list();
    }

    public List<HistoryEntry> history(UUID projectId) {
        return jdbc.sql("""
                SELECT u.id, u.first_name, u.last_name, m.role, m.joined_at, m.left_at, m.changed_by
                FROM project_member m JOIN app_user u ON u.id = m.user_id
                WHERE m.project_id = :p ORDER BY m.joined_at, m.left_at NULLS LAST, u.id
                """)
            .param("p", projectId)
            .query((rs, n) -> new HistoryEntry(rs.getObject("id", UUID.class), rs.getString("first_name"),
                rs.getString("last_name"), rs.getString("role"), rs.getObject("joined_at", OffsetDateTime.class),
                rs.getObject("left_at", OffsetDateTime.class), rs.getObject("changed_by", UUID.class)))
            .list();
    }

    /** Enrolled students of the class who are on no ACTIVE project, except {@code exclude}. */
    public List<Classmate> eligibleClassmates(UUID classId, UUID exclude) {
        return jdbc.sql("""
                SELECT u.id, u.first_name, u.last_name FROM enrollment e JOIN app_user u ON u.id = e.student_id
                WHERE e.class_id = :c AND e.status = 'ENROLLED' AND u.status <> 'DEACTIVATED' AND u.id <> :x
                  AND NOT EXISTS (SELECT 1 FROM project p JOIN project_member m ON m.project_id = p.id AND m.left_at IS NULL
                                  WHERE p.class_id = :c AND p.status = 'ACTIVE' AND m.user_id = u.id)
                ORDER BY u.last_name, u.first_name, u.id
                """)
            .param("c", classId).param("x", exclude)
            .query((rs, n) -> new Classmate(rs.getObject("id", UUID.class), rs.getString("first_name"), rs.getString("last_name")))
            .list();
    }

    // ---- invitations ----

    public UUID insertInvitation(UUID projectId, UUID invitedUser, UUID invitedBy, int validDays) {
        return jdbc.sql("""
                INSERT INTO team_invitation (project_id, invited_user_id, invited_by, expires_at)
                VALUES (:p, :u, :by, now() + make_interval(days => :d)) RETURNING id
                """)
            .param("p", projectId).param("u", invitedUser).param("by", invitedBy).param("d", validDays)
            .query(UUID.class).single();
    }

    public Optional<InvitationRow> findInvitation(UUID id, boolean lock) {
        return jdbc.sql("SELECT id, project_id, invited_user_id, invited_by, status, expires_at FROM team_invitation WHERE id = :id"
                + (lock ? " FOR UPDATE" : ""))
            .param("id", id)
            .query((rs, n) -> new InvitationRow(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("invited_user_id", UUID.class), rs.getObject("invited_by", UUID.class),
                rs.getString("status"), rs.getObject("expires_at", OffsetDateTime.class)))
            .optional();
    }

    public void setInvitationStatus(UUID id, String status) {
        jdbc.sql("UPDATE team_invitation SET status = :s, responded_at = now() WHERE id = :id AND status = 'PENDING'")
            .param("id", id).param("s", status).update();
    }

    /** Live (pending, unexpired) invitations of a project. */
    public List<InvitationView> pendingInvitations(UUID projectId) {
        return jdbc.sql("""
                SELECT i.id, u.id AS student_id, u.first_name, u.last_name, i.status, i.expires_at, i.created_at
                FROM team_invitation i JOIN app_user u ON u.id = i.invited_user_id
                WHERE i.project_id = :p AND i.status = 'PENDING' AND i.expires_at > now()
                ORDER BY i.created_at, i.id
                """)
            .param("p", projectId)
            .query((rs, n) -> new InvitationView(rs.getObject("id", UUID.class), rs.getObject("student_id", UUID.class),
                rs.getString("first_name"), rs.getString("last_name"), rs.getString("status"),
                rs.getObject("expires_at", OffsetDateTime.class), rs.getObject("created_at", OffsetDateTime.class)))
            .list();
    }

    public int livePendingCount(UUID projectId) {
        return jdbc.sql("SELECT count(*) FROM team_invitation WHERE project_id = :p AND status = 'PENDING' AND expires_at > now()")
            .param("p", projectId).query(Integer.class).single();
    }

    public List<MyInvitation> pendingFor(UUID userId) {
        return jdbc.sql("""
                SELECT i.id, p.id AS project_id, p.class_id, c.subject_code, p.title, p.team_name,
                       ib.first_name || ' ' || ib.last_name AS invited_by, i.expires_at
                FROM team_invitation i
                JOIN project p ON p.id = i.project_id AND p.status = 'ACTIVE'
                JOIN academic_class c ON c.id = p.class_id AND c.status = 'ACTIVE'
                JOIN app_user ib ON ib.id = i.invited_by
                WHERE i.invited_user_id = :u AND i.status = 'PENDING' AND i.expires_at > now()
                ORDER BY i.created_at, i.id
                """)
            .param("u", userId)
            .query((rs, n) -> new MyInvitation(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("class_id", UUID.class), rs.getString("subject_code"), rs.getString("title"),
                rs.getString("team_name"), rs.getString("invited_by"), rs.getObject("expires_at", OffsetDateTime.class)))
            .list();
    }

    /** Cancels the user's pending invitations to projects of the class (optionally keeping one project's). */
    public int cancelPendingForUserInClass(UUID classId, UUID userId) {
        return jdbc.sql("""
                UPDATE team_invitation SET status = 'CANCELLED', responded_at = now()
                WHERE invited_user_id = :u AND status = 'PENDING'
                  AND project_id IN (SELECT id FROM project WHERE class_id = :c)
                """)
            .param("u", userId).param("c", classId).update();
    }

    public int cancelAllPendingForProject(UUID projectId) {
        return jdbc.sql("UPDATE team_invitation SET status = 'CANCELLED', responded_at = now() WHERE project_id = :p AND status = 'PENDING'")
            .param("p", projectId).update();
    }
}
