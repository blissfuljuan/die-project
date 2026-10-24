package com.blissfuljuan.dieapi.academic.repository;

import com.blissfuljuan.dieapi.academic.model.ClassStatus;
import com.blissfuljuan.dieapi.academic.model.EnrollmentStatus;
import com.blissfuljuan.dieapi.academic.model.LockedClass;
import com.blissfuljuan.dieapi.academic.model.NewClass;
import com.blissfuljuan.dieapi.academic.model.StudentRef;
import com.blissfuljuan.dieapi.academic.model.TeamFormation;
import com.blissfuljuan.dieapi.account.model.AccountStatus;
import com.blissfuljuan.dieapi.account.model.UserRole;
import com.blissfuljuan.dieapi.academic.view.ClassPage;
import com.blissfuljuan.dieapi.academic.view.ClassView;
import com.blissfuljuan.dieapi.academic.view.ClassView.CoordinatorRef;
import com.blissfuljuan.dieapi.academic.view.CoordinatorCandidate;
import com.blissfuljuan.dieapi.academic.view.CoordinatorEntry;
import com.blissfuljuan.dieapi.access.model.WorkspaceCapability;
import com.blissfuljuan.dieapi.academic.view.EnrollmentView;
import com.blissfuljuan.dieapi.access.model.SqlScope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

/** SQL for classes, coordinators and enrollments. No authorization decisions here except the SqlScope it is given. */
@Repository
public class ClassRepository {

    private static final String CLASS_COLUMNS = """
        c.id, c.academic_year, c.semester, c.subject_code, c.subject_name, c.section, c.status,
        c.team_formation, c.team_min_size, c.team_max_size, c.students_may_change_teams, c.version, c.owner_id,
        (SELECT count(*) FROM enrollment e WHERE e.class_id = c.id AND e.status = 'ENROLLED') AS enrolled_count
        """;

    private final JdbcClient jdbc;

    public ClassRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static ClassView mapClass(ResultSet rs, int n) throws SQLException {
        return new ClassView(rs.getObject("id", UUID.class), rs.getString("academic_year"),
            rs.getString("semester"), rs.getString("subject_code"), rs.getString("subject_name"),
            rs.getString("section"), rs.getString("status"), rs.getString("team_formation"),
            rs.getInt("team_min_size"), rs.getInt("team_max_size"), rs.getBoolean("students_may_change_teams"),
            rs.getInt("enrolled_count"), rs.getLong("version"), rs.getObject("owner_id", UUID.class), List.of(), List.of());
    }

    // ---- classes ----

    public ClassPage search(SqlScope scope, ClassStatus status, String q, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        Map<String, Object> params = new HashMap<>(scope.params());
        if (status != null) { where.append(" AND c.status = :status"); params.put("status", status.name()); }
        if (q != null && !q.isBlank()) {
            String escaped = q.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            where.append("""
                 AND lower(c.subject_code || ' ' || c.subject_name || ' ' || c.section || ' ' || c.academic_year)
                     LIKE :q ESCAPE '\\'
                """);
            params.put("q", "%" + escaped + "%");
        }
        String from = " FROM academic_class c " + scope.join() + where;
        long total = jdbc.sql("SELECT count(*)" + from).params(params).query(Long.class).single();
        List<ClassView> items = jdbc.sql("SELECT " + CLASS_COLUMNS + from
                + " ORDER BY c.academic_year DESC, c.semester, c.subject_code, c.section, c.id LIMIT :limit OFFSET :offset")
            .params(params).param("limit", size).param("offset", (long) page * size)
            .query(ClassRepository::mapClass).list();
        return new ClassPage(withCoordinators(items), page, size, total);
    }

    public Optional<ClassView> find(UUID id) {
        return jdbc.sql("SELECT " + CLASS_COLUMNS + " FROM academic_class c WHERE c.id = :id")
            .param("id", id).query(ClassRepository::mapClass).optional()
            .map(v -> withCoordinators(List.of(v)).get(0));
    }

    public Optional<LockedClass> lock(UUID id) {
        return load(id, true);
    }

    /** Same row as {@link #lock} without taking the row lock (safe in read-only transactions). */
    public Optional<LockedClass> peek(UUID id) {
        return load(id, false);
    }

    private Optional<LockedClass> load(UUID id, boolean forUpdate) {
        return jdbc.sql("""
                SELECT id, status, version, academic_year, semester, subject_code, subject_name, section,
                       team_formation, team_min_size, team_max_size, students_may_change_teams
                FROM academic_class WHERE id = :id""" + (forUpdate ? " FOR UPDATE" : ""))
            .param("id", id)
            .query((rs, n) -> new LockedClass(rs.getObject("id", UUID.class), ClassStatus.valueOf(rs.getString("status")),
                rs.getLong("version"), rs.getString("academic_year"), rs.getString("semester"),
                rs.getString("subject_code"), rs.getString("subject_name"), rs.getString("section"),
                TeamFormation.valueOf(rs.getString("team_formation")), rs.getInt("team_min_size"), rs.getInt("team_max_size"),
                rs.getBoolean("students_may_change_teams")))
            .optional();
    }

    /** Inserts the workspace; the Owner must be inserted as a coordinator in the same transaction (deferred FK). */
    public UUID insert(NewClass c, UUID ownerId) {
        return jdbc.sql("""
                INSERT INTO academic_class (academic_year, semester, subject_code, subject_name, section,
                       team_formation, team_min_size, team_max_size, students_may_change_teams, created_by, owner_id)
                VALUES (:y, :sem, :code, :name, :sec, :tf, :min, :max, :chg, :by, :owner) RETURNING id
                """)
            .param("y", c.academicYear()).param("sem", c.semester()).param("code", c.subjectCode())
            .param("name", c.subjectName()).param("sec", c.section()).param("tf", c.teamFormation().name())
            .param("min", c.teamMinSize()).param("max", c.teamMaxSize())
            .param("chg", c.participantsMayChangeTeams()).param("by", c.createdBy()).param("owner", ownerId)
            .query(UUID.class).single();
    }

    public void update(UUID id, NewClass c) {
        jdbc.sql("""
                UPDATE academic_class SET academic_year = :y, semester = :sem, subject_code = :code,
                       subject_name = :name, section = :sec, team_formation = :tf, team_min_size = :min,
                       team_max_size = :max, students_may_change_teams = :chg,
                       updated_at = now(), version = version + 1
                WHERE id = :id
                """)
            .param("id", id).param("y", c.academicYear()).param("sem", c.semester())
            .param("code", c.subjectCode()).param("name", c.subjectName()).param("sec", c.section())
            .param("tf", c.teamFormation().name()).param("min", c.teamMinSize()).param("max", c.teamMaxSize())
            .param("chg", c.participantsMayChangeTeams())
            .update();
    }

    public void setStatus(UUID id, ClassStatus status) {
        jdbc.sql("UPDATE academic_class SET status = :s, updated_at = now(), version = version + 1 WHERE id = :id")
            .param("id", id).param("s", status.name()).update();
    }

    // ---- coordinators ----

    public void insertCoordinator(UUID workspaceId, UUID userId, Collection<WorkspaceCapability> capabilities) {
        jdbc.sql("INSERT INTO class_instructor (class_id, user_id, capabilities) VALUES (:c, :u, CAST(:caps AS text[]))")
            .param("c", workspaceId).param("u", userId).param("caps", names(capabilities)).update();
    }

    public boolean isAssigned(UUID workspaceId, UUID userId) {
        return jdbc.sql("SELECT 1 FROM class_instructor WHERE class_id = :c AND user_id = :u")
            .param("c", workspaceId).param("u", userId).query(Integer.class).optional().isPresent();
    }

    public void deleteCoordinator(UUID workspaceId, UUID userId) {
        jdbc.sql("DELETE FROM class_instructor WHERE class_id = :c AND user_id = :u")
            .param("c", workspaceId).param("u", userId).update();
    }

    public void setCapabilities(UUID workspaceId, UUID userId, Collection<WorkspaceCapability> capabilities) {
        jdbc.sql("UPDATE class_instructor SET capabilities = CAST(:caps AS text[]) WHERE class_id = :c AND user_id = :u")
            .param("c", workspaceId).param("u", userId).param("caps", names(capabilities)).update();
    }

    private static String[] names(Collection<WorkspaceCapability> capabilities) {
        return capabilities.stream().map(Enum::name).sorted().toArray(String[]::new);
    }

    /** The designated Owner (read it after taking the workspace lock when deciding anything with it). */
    public Optional<UUID> ownerId(UUID workspaceId) {
        return jdbc.sql("SELECT owner_id FROM academic_class WHERE id = :c").param("c", workspaceId).query(UUID.class).optional();
    }

    public void updateOwner(UUID workspaceId, UUID ownerId) {
        jdbc.sql("UPDATE academic_class SET owner_id = :o, updated_at = now(), version = version + 1 WHERE id = :c")
            .param("c", workspaceId).param("o", ownerId).update();
    }

    /** Number of ACTIVE workspaces the user is the Owner of (archived ones never block account changes). */
    public int countActiveOwnedBy(UUID userId) {
        return jdbc.sql("SELECT count(*) FROM academic_class WHERE owner_id = :u AND status = 'ACTIVE'")
            .param("u", userId).query(Integer.class).single();
    }

    /** True if the workspace's Owner is still an active (non-deactivated) COORDINATOR account. */
    public boolean ownerIsActiveCoordinator(UUID workspaceId) {
        return jdbc.sql("""
                SELECT 1 FROM academic_class c JOIN app_user u ON u.id = c.owner_id
                WHERE c.id = :c AND u.role = 'COORDINATOR' AND u.status <> 'DEACTIVATED'
                """).param("c", workspaceId).query(Integer.class).optional().isPresent();
    }

    private static final String COORDINATOR_ENTRY_SQL = """
        SELECT ci.user_id, u.first_name, u.last_name, (c.owner_id = ci.user_id) AS is_owner, ci.capabilities, ci.assigned_at
        FROM class_instructor ci JOIN app_user u ON u.id = ci.user_id JOIN academic_class c ON c.id = ci.class_id
        """;

    public List<CoordinatorEntry> coordinatorEntries(UUID workspaceId) {
        return jdbc.sql(COORDINATOR_ENTRY_SQL
                + " WHERE ci.class_id = :c ORDER BY (c.owner_id = ci.user_id) DESC, u.last_name, u.first_name, u.id")
            .param("c", workspaceId).query(ClassRepository::mapEntry).list();
    }

    public Optional<CoordinatorEntry> coordinatorEntry(UUID workspaceId, UUID userId) {
        return jdbc.sql(COORDINATOR_ENTRY_SQL + " WHERE ci.class_id = :c AND ci.user_id = :u")
            .param("c", workspaceId).param("u", userId).query(ClassRepository::mapEntry).optional();
    }

    /** The Owner is shown with every capability (implicit); others with what is stored, in enum order. */
    private static CoordinatorEntry mapEntry(ResultSet rs, int n) throws SQLException {
        boolean owner = rs.getBoolean("is_owner");
        Set<String> stored = new HashSet<>(Arrays.asList((String[]) rs.getArray("capabilities").getArray()));
        List<String> caps = Arrays.stream(WorkspaceCapability.values()).map(Enum::name)
            .filter(name -> owner || stored.contains(name)).toList();
        return new CoordinatorEntry(rs.getObject("user_id", UUID.class), rs.getString("first_name"), rs.getString("last_name"),
            owner, caps, rs.getObject("assigned_at", OffsetDateTime.class));
    }

    /**
     * Number of the given ids that are real, non-deactivated COORDINATOR accounts. The rows are locked FOR SHARE so
     * that a concurrent deactivation / role change of the same account (which takes the row FOR UPDATE) cannot
     * slip between this check and the commit of the assignment or ownership transfer that relies on it.
     */
    /**
     * Active coordinator accounts not yet assigned to the workspace whose email or name starts with {@code prefix}
     * (already lower-cased and LIKE-escaped by the caller). Few rows, for a picker.
     */
    public List<CoordinatorCandidate> coordinatorCandidates(UUID workspaceId, String prefix, int limit) {
        return jdbc.sql("""
                SELECT u.id, u.first_name, u.last_name, u.email FROM app_user u
                WHERE u.role = 'COORDINATOR' AND u.status <> 'DEACTIVATED'
                  AND NOT EXISTS (SELECT 1 FROM class_instructor ci WHERE ci.class_id = :c AND ci.user_id = u.id)
                  AND (lower(u.email) LIKE :p ESCAPE '\\' OR lower(u.last_name) LIKE :p ESCAPE '\\' OR lower(u.first_name) LIKE :p ESCAPE '\\')
                ORDER BY u.last_name, u.first_name, u.id LIMIT :limit
                """)
            .param("c", workspaceId).param("p", prefix + "%").param("limit", limit)
            .query((rs, n) -> new CoordinatorCandidate(rs.getObject("id", UUID.class), rs.getString("first_name"),
                rs.getString("last_name"), rs.getString("email")))
            .list();
    }

    public long countAssignableInstructors(Collection<UUID> ids) {
        if (ids.isEmpty()) return 0;
        return jdbc.sql("""
                SELECT count(*) FROM (
                    SELECT id FROM app_user WHERE id IN (:ids) AND role = 'COORDINATOR' AND status <> 'DEACTIVATED'
                    ORDER BY id FOR SHARE) locked
                """)
            .param("ids", ids).query(Long.class).single();
    }

    private List<ClassView> withCoordinators(List<ClassView> classes) {
        if (classes.isEmpty()) return classes;
        List<UUID> ids = classes.stream().map(ClassView::id).toList();
        Map<UUID, List<CoordinatorRef>> byClass = new HashMap<>();
        jdbc.sql("""
                SELECT ci.class_id, u.id, u.first_name, u.last_name, (c.owner_id = ci.user_id) AS is_owner
                FROM class_instructor ci JOIN app_user u ON u.id = ci.user_id JOIN academic_class c ON c.id = ci.class_id
                WHERE ci.class_id IN (:ids) ORDER BY u.last_name, u.first_name, u.id
                """)
            .param("ids", ids)
            .query((rs, n) -> {
                byClass.computeIfAbsent(rs.getObject("class_id", UUID.class), k -> new ArrayList<>())
                    .add(new CoordinatorRef(rs.getObject("id", UUID.class), rs.getString("first_name"),
                        rs.getString("last_name"), rs.getBoolean("is_owner")));
                return 0;
            }).list();
        return classes.stream().map(c -> c.withCoordinators(byClass.getOrDefault(c.id(), List.of())))
            .collect(Collectors.toList());
    }

    // ---- enrollments ----

    public List<EnrollmentView> enrollments(UUID workspaceId, boolean includeRemoved, int limit, int offset) {
        return jdbc.sql("""
                SELECT u.id, u.student_number, u.last_name, u.first_name, u.middle_name, u.email,
                       u.status AS account_status, e.program, e.section, e.status, e.enrolled_at, e.removed_at
                FROM enrollment e JOIN app_user u ON u.id = e.student_id
                WHERE e.class_id = :c AND (:all OR e.status = 'ENROLLED')
                ORDER BY u.last_name, u.first_name, u.id
                LIMIT :limit OFFSET :offset
                """)
            .param("c", workspaceId).param("all", includeRemoved).param("limit", limit).param("offset", offset)
            .query((rs, n) -> new EnrollmentView(rs.getObject("id", UUID.class), rs.getString("student_number"),
                rs.getString("last_name"), rs.getString("first_name"), rs.getString("middle_name"),
                rs.getString("email"), rs.getString("account_status"), rs.getString("program"),
                rs.getString("section"), rs.getString("status"),
                rs.getObject("enrolled_at", java.time.OffsetDateTime.class),
                rs.getObject("removed_at", java.time.OffsetDateTime.class)))
            .list();
    }

    public long countEnrollments(UUID workspaceId, boolean includeRemoved) {
        return jdbc.sql("SELECT count(*) FROM enrollment WHERE class_id = :c AND (:all OR status = 'ENROLLED')")
            .param("c", workspaceId).param("all", includeRemoved).query(Long.class).single();
    }

    public Optional<StudentRef> findStudentById(UUID id) {
        return jdbc.sql("SELECT id, role, status, last_name, created_by FROM app_user WHERE id = :id")
            .param("id", id).query(ClassRepository::mapStudent).optional();
    }

    /** Student numbers are unique ignoring case. */
    public Optional<StudentRef> findStudentByNumber(String studentNumber) {
        return jdbc.sql("SELECT id, role, status, last_name, created_by FROM app_user WHERE lower(student_number) = lower(:n)")
            .param("n", studentNumber).query(ClassRepository::mapStudent).optional();
    }

    private static StudentRef mapStudent(ResultSet rs, int n) throws SQLException {
        return new StudentRef(rs.getObject("id", UUID.class), UserRole.valueOf(rs.getString("role")),
            AccountStatus.valueOf(rs.getString("status")),
            rs.getString("last_name"), rs.getObject("created_by", UUID.class));
    }

    /** True if the student is (or was) enrolled in any class the instructor is assigned to. */
    public boolean studentKnownToInstructor(UUID instructorId, UUID participantId) {
        return jdbc.sql("""
                SELECT 1 FROM enrollment e JOIN class_instructor ci ON ci.class_id = e.class_id
                WHERE ci.user_id = :i AND e.student_id = :s LIMIT 1
                """)
            .param("i", instructorId).param("s", participantId).query(Integer.class).optional().isPresent();
    }

    /** @return current enrollment status for the pair, if a row exists. */
    public Optional<EnrollmentStatus> enrollmentStatus(UUID workspaceId, UUID participantId) {
        return jdbc.sql("SELECT status FROM enrollment WHERE class_id = :c AND student_id = :s FOR UPDATE")
            .param("c", workspaceId).param("s", participantId).query(String.class).optional().map(EnrollmentStatus::valueOf);
    }

    public void insertEnrollment(UUID workspaceId, UUID participantId, String program, String section) {
        jdbc.sql("INSERT INTO enrollment (class_id, student_id, program, section) VALUES (:c, :s, :p, :sec)")
            .param("c", workspaceId).param("s", participantId).param("p", program).param("sec", section).update();
    }

    public void reinstateEnrollment(UUID workspaceId, UUID participantId, String program, String section) {
        jdbc.sql("""
                UPDATE enrollment SET status = 'ENROLLED', removed_at = NULL, enrolled_at = now(),
                       program = :p, section = :sec
                WHERE class_id = :c AND student_id = :s
                """)
            .param("c", workspaceId).param("s", participantId).param("p", program).param("sec", section).update();
    }

    public void removeEnrollment(UUID workspaceId, UUID participantId) {
        jdbc.sql("UPDATE enrollment SET status = 'REMOVED', removed_at = now() WHERE class_id = :c AND student_id = :s")
            .param("c", workspaceId).param("s", participantId).update();
    }
}
