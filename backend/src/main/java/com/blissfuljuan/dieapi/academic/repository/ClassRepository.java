package com.blissfuljuan.dieapi.academic.repository;

import com.blissfuljuan.dieapi.academic.model.LockedClass;
import com.blissfuljuan.dieapi.academic.model.NewClass;
import com.blissfuljuan.dieapi.academic.model.StudentRef;
import com.blissfuljuan.dieapi.academic.view.ClassPage;
import com.blissfuljuan.dieapi.academic.view.ClassView;
import com.blissfuljuan.dieapi.academic.view.ClassView.InstructorRef;
import com.blissfuljuan.dieapi.academic.view.EnrollmentView;
import com.blissfuljuan.dieapi.access.model.SqlScope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

/** SQL for classes, instructors and enrollments. No authorization decisions here except the SqlScope it is given. */
@Repository
public class ClassRepository {

    private static final String CLASS_COLUMNS = """
        c.id, c.academic_year, c.semester, c.subject_code, c.subject_name, c.section, c.status,
        c.team_formation, c.team_min_size, c.team_max_size, c.students_may_change_teams, c.version,
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
            rs.getInt("enrolled_count"), rs.getLong("version"), List.of());
    }

    // ---- classes ----

    public ClassPage search(SqlScope scope, String status, String q, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        Map<String, Object> params = new HashMap<>(scope.params());
        if (status != null) { where.append(" AND c.status = :status"); params.put("status", status); }
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
        return new ClassPage(withInstructors(items), page, size, total);
    }

    public Optional<ClassView> find(UUID id) {
        return jdbc.sql("SELECT " + CLASS_COLUMNS + " FROM academic_class c WHERE c.id = :id")
            .param("id", id).query(ClassRepository::mapClass).optional()
            .map(v -> withInstructors(List.of(v)).get(0));
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
            .query((rs, n) -> new LockedClass(rs.getObject("id", UUID.class), rs.getString("status"),
                rs.getLong("version"), rs.getString("academic_year"), rs.getString("semester"),
                rs.getString("subject_code"), rs.getString("subject_name"), rs.getString("section"),
                rs.getString("team_formation"), rs.getInt("team_min_size"), rs.getInt("team_max_size"),
                rs.getBoolean("students_may_change_teams")))
            .optional();
    }

    public UUID insert(NewClass c) {
        return jdbc.sql("""
                INSERT INTO academic_class (academic_year, semester, subject_code, subject_name, section,
                       team_formation, team_min_size, team_max_size, students_may_change_teams, created_by)
                VALUES (:y, :sem, :code, :name, :sec, :tf, :min, :max, :chg, :by) RETURNING id
                """)
            .param("y", c.academicYear()).param("sem", c.semester()).param("code", c.subjectCode())
            .param("name", c.subjectName()).param("sec", c.section()).param("tf", c.teamFormation())
            .param("min", c.teamMinSize()).param("max", c.teamMaxSize())
            .param("chg", c.studentsMayChangeTeams()).param("by", c.createdBy())
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
            .param("tf", c.teamFormation()).param("min", c.teamMinSize()).param("max", c.teamMaxSize())
            .param("chg", c.studentsMayChangeTeams())
            .update();
    }

    public void setStatus(UUID id, String status) {
        jdbc.sql("UPDATE academic_class SET status = :s, updated_at = now(), version = version + 1 WHERE id = :id")
            .param("id", id).param("s", status).update();
    }

    // ---- instructors ----

    public List<UUID> instructorIds(UUID classId) {
        return jdbc.sql("SELECT user_id FROM class_instructor WHERE class_id = :c ORDER BY user_id")
            .param("c", classId).query(UUID.class).list();
    }

    public void replaceInstructors(UUID classId, Collection<UUID> instructorIds) {
        jdbc.sql("DELETE FROM class_instructor WHERE class_id = :c").param("c", classId).update();
        for (UUID id : instructorIds) {
            jdbc.sql("INSERT INTO class_instructor (class_id, user_id) VALUES (:c, :u)")
                .param("c", classId).param("u", id).update();
        }
    }

    /** Number of the given ids that are real, non-deactivated INSTRUCTOR accounts. */
    public long countAssignableInstructors(Collection<UUID> ids) {
        if (ids.isEmpty()) return 0;
        return jdbc.sql("SELECT count(*) FROM app_user WHERE id IN (:ids) AND role = 'INSTRUCTOR' AND status <> 'DEACTIVATED'")
            .param("ids", ids).query(Long.class).single();
    }

    private List<ClassView> withInstructors(List<ClassView> classes) {
        if (classes.isEmpty()) return classes;
        List<UUID> ids = classes.stream().map(ClassView::id).toList();
        Map<UUID, List<InstructorRef>> byClass = new HashMap<>();
        jdbc.sql("""
                SELECT ci.class_id, u.id, u.first_name, u.last_name
                FROM class_instructor ci JOIN app_user u ON u.id = ci.user_id
                WHERE ci.class_id IN (:ids) ORDER BY u.last_name, u.first_name, u.id
                """)
            .param("ids", ids)
            .query((rs, n) -> {
                byClass.computeIfAbsent(rs.getObject("class_id", UUID.class), k -> new ArrayList<>())
                    .add(new InstructorRef(rs.getObject("id", UUID.class), rs.getString("first_name"),
                        rs.getString("last_name")));
                return 0;
            }).list();
        return classes.stream().map(c -> c.withInstructors(byClass.getOrDefault(c.id(), List.of())))
            .collect(Collectors.toList());
    }

    // ---- enrollments ----

    public List<EnrollmentView> enrollments(UUID classId, boolean includeRemoved) {
        return jdbc.sql("""
                SELECT u.id, u.student_number, u.last_name, u.first_name, u.middle_name, u.email,
                       u.status AS account_status, e.program, e.section, e.status, e.enrolled_at, e.removed_at
                FROM enrollment e JOIN app_user u ON u.id = e.student_id
                WHERE e.class_id = :c AND (:all OR e.status = 'ENROLLED')
                ORDER BY u.last_name, u.first_name, u.id
                """)
            .param("c", classId).param("all", includeRemoved)
            .query((rs, n) -> new EnrollmentView(rs.getObject("id", UUID.class), rs.getString("student_number"),
                rs.getString("last_name"), rs.getString("first_name"), rs.getString("middle_name"),
                rs.getString("email"), rs.getString("account_status"), rs.getString("program"),
                rs.getString("section"), rs.getString("status"),
                rs.getObject("enrolled_at", java.time.OffsetDateTime.class),
                rs.getObject("removed_at", java.time.OffsetDateTime.class)))
            .list();
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
        return new StudentRef(rs.getObject("id", UUID.class), rs.getString("role"), rs.getString("status"),
            rs.getString("last_name"), rs.getObject("created_by", UUID.class));
    }

    /** True if the student is (or was) enrolled in any class the instructor is assigned to. */
    public boolean studentKnownToInstructor(UUID instructorId, UUID studentId) {
        return jdbc.sql("""
                SELECT 1 FROM enrollment e JOIN class_instructor ci ON ci.class_id = e.class_id
                WHERE ci.user_id = :i AND e.student_id = :s LIMIT 1
                """)
            .param("i", instructorId).param("s", studentId).query(Integer.class).optional().isPresent();
    }

    /** @return current enrollment status for the pair, if a row exists. */
    public Optional<String> enrollmentStatus(UUID classId, UUID studentId) {
        return jdbc.sql("SELECT status FROM enrollment WHERE class_id = :c AND student_id = :s FOR UPDATE")
            .param("c", classId).param("s", studentId).query(String.class).optional();
    }

    public void insertEnrollment(UUID classId, UUID studentId, String program, String section) {
        jdbc.sql("INSERT INTO enrollment (class_id, student_id, program, section) VALUES (:c, :s, :p, :sec)")
            .param("c", classId).param("s", studentId).param("p", program).param("sec", section).update();
    }

    public void reinstateEnrollment(UUID classId, UUID studentId, String program, String section) {
        jdbc.sql("""
                UPDATE enrollment SET status = 'ENROLLED', removed_at = NULL, enrolled_at = now(),
                       program = :p, section = :sec
                WHERE class_id = :c AND student_id = :s
                """)
            .param("c", classId).param("s", studentId).param("p", program).param("sec", section).update();
    }

    public void removeEnrollment(UUID classId, UUID studentId) {
        jdbc.sql("UPDATE enrollment SET status = 'REMOVED', removed_at = now() WHERE class_id = :c AND student_id = :s")
            .param("c", classId).param("s", studentId).update();
    }
}
