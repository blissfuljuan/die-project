package com.blissfuljuan.die.roster.repository;

import com.blissfuljuan.die.roster.model.ExistingUser;
import com.blissfuljuan.die.roster.model.ImportHeader;
import com.blissfuljuan.die.roster.model.StoredRow;
import com.blissfuljuan.die.roster.view.Summary;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RosterImportRepository {

    private static final String ROW_COLUMNS = """
        row_number, student_number, last_name, first_name, middle_name, email, program, section,
        action, conflict_kind, resolution, message, user_id
        """;

    private final JdbcClient jdbc;

    public RosterImportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- lookups used by classification ----

    /** Keyed by lower-cased student number. */
    public Map<String, ExistingUser> usersByStudentNumber(Collection<String> lowerNumbers) {
        Map<String, ExistingUser> out = new HashMap<>();
        if (lowerNumbers.isEmpty()) return out;
        jdbc.sql("""
                SELECT id, role, status, last_name, first_name, created_by, lower(student_number) AS num
                FROM app_user WHERE lower(student_number) IN (:n)
                """)
            .param("n", lowerNumbers)
            .query((rs, i) -> {
                out.put(rs.getString("num"), new ExistingUser(rs.getObject("id", UUID.class), rs.getString("role"),
                    rs.getString("status"), rs.getString("last_name"), rs.getString("first_name"),
                    rs.getObject("created_by", UUID.class)));
                return 0;
            }).list();
        return out;
    }

    /** Of the given students, those enrolled (now or before) in a class the instructor is assigned to. */
    public java.util.Set<UUID> studentsKnownToInstructor(UUID instructorId, Collection<UUID> studentIds) {
        if (studentIds.isEmpty()) return java.util.Set.of();
        return new java.util.HashSet<>(jdbc.sql("""
                SELECT DISTINCT e.student_id FROM enrollment e JOIN class_instructor ci ON ci.class_id = e.class_id
                WHERE ci.user_id = :i AND e.student_id IN (:s)
                """)
            .param("i", instructorId).param("s", studentIds).query(UUID.class).list());
    }

    /** Student id -> enrollment status for the class (ENROLLED / REMOVED). */
    public Map<UUID, String> enrollmentStatuses(UUID classId) {
        Map<UUID, String> out = new HashMap<>();
        jdbc.sql("SELECT student_id, status FROM enrollment WHERE class_id = :c").param("c", classId)
            .query((rs, i) -> {
                out.put(rs.getObject("student_id", UUID.class), rs.getString("status"));
                return 0;
            }).list();
        return out;
    }

    // ---- imports ----

    public UUID insertImport(UUID classId, UUID uploadedBy, String fileName, String sha256) {
        return jdbc.sql("""
                INSERT INTO roster_import (class_id, uploaded_by, file_name, file_sha256)
                VALUES (:c, :u, :f, :h) RETURNING id
                """)
            .param("c", classId).param("u", uploadedBy).param("f", fileName).param("h", sha256)
            .query(UUID.class).single();
    }

    public void insertRow(UUID importId, StoredRow r) {
        jdbc.sql("""
                INSERT INTO roster_import_row (import_id, row_number, student_number, last_name, first_name,
                       middle_name, email, program, section, action, conflict_kind, message, user_id)
                VALUES (:i, :n, :sn, :ln, :fn, :mn, :em, :pr, :se, :a, :k, :m, :u)
                """)
            .param("i", importId).param("n", r.rowNumber()).param("sn", r.studentNumber()).param("ln", r.lastName())
            .param("fn", r.firstName()).param("mn", r.middleName()).param("em", r.email()).param("pr", r.program())
            .param("se", r.section()).param("a", r.action()).param("k", r.conflictKind()).param("m", r.message())
            .param("u", r.matchedUserId())
            .update();
    }

    public Optional<ImportHeader> find(UUID importId, boolean lock) {
        return jdbc.sql("""
                SELECT id, class_id, uploaded_by, file_name, status, created_at, committed_at, summary::text AS summary
                FROM roster_import WHERE id = :id""" + (lock ? " FOR UPDATE" : ""))
            .param("id", importId)
            .query((rs, n) -> new ImportHeader(rs.getObject("id", UUID.class), rs.getObject("class_id", UUID.class),
                rs.getObject("uploaded_by", UUID.class), rs.getString("file_name"), rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("committed_at", OffsetDateTime.class),
                rs.getString("summary")))
            .optional();
    }

    public List<ImportHeader> listForClass(UUID classId) {
        return jdbc.sql("""
                SELECT id, class_id, uploaded_by, file_name, status, created_at, committed_at, summary::text AS summary
                FROM roster_import WHERE class_id = :c ORDER BY created_at DESC, id
                """)
            .param("c", classId)
            .query((rs, n) -> new ImportHeader(rs.getObject("id", UUID.class), rs.getObject("class_id", UUID.class),
                rs.getObject("uploaded_by", UUID.class), rs.getString("file_name"), rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("committed_at", OffsetDateTime.class),
                rs.getString("summary")))
            .list();
    }

    public void setStatus(UUID importId, String status) {
        jdbc.sql("UPDATE roster_import SET status = :s WHERE id = :id").param("id", importId).param("s", status).update();
    }

    public void markCommitted(UUID importId, UUID by, String summaryJson) {
        jdbc.sql("""
                UPDATE roster_import SET status = 'COMMITTED', committed_at = now(), committed_by = :by,
                       summary = CAST(:s AS jsonb)
                WHERE id = :id
                """)
            .param("id", importId).param("by", by).param("s", summaryJson).update();
    }

    // ---- rows ----

    public List<StoredRow> allRows(UUID importId) {
        return jdbc.sql("SELECT " + ROW_COLUMNS + " FROM roster_import_row WHERE import_id = :i ORDER BY row_number")
            .param("i", importId).query(RosterImportRepository::mapRow).list();
    }

    public List<StoredRow> rows(UUID importId, String action, int page, int size) {
        String filter = action == null ? "" : " AND action = :a";
        var spec = jdbc.sql("SELECT " + ROW_COLUMNS + " FROM roster_import_row WHERE import_id = :i" + filter
                + " ORDER BY row_number LIMIT :limit OFFSET :offset")
            .param("i", importId).param("limit", size).param("offset", (long) page * size);
        if (action != null) spec = spec.param("a", action);
        return spec.query(RosterImportRepository::mapRow).list();
    }

    public long countRows(UUID importId, String action) {
        var spec = jdbc.sql("SELECT count(*) FROM roster_import_row WHERE import_id = :i"
                + (action == null ? "" : " AND action = :a")).param("i", importId);
        if (action != null) spec = spec.param("a", action);
        return spec.query(Long.class).single();
    }

    public Summary summary(UUID importId) {
        Map<String, Integer> byAction = new HashMap<>();
        int[] unresolved = {0};
        jdbc.sql("""
                SELECT action, count(*) AS n, count(*) FILTER (WHERE action = 'CONFLICT' AND resolution IS NULL) AS open
                FROM roster_import_row WHERE import_id = :i GROUP BY action
                """)
            .param("i", importId)
            .query((rs, i) -> {
                byAction.put(rs.getString("action"), rs.getInt("n"));
                unresolved[0] += rs.getInt("open");
                return 0;
            }).list();
        int total = byAction.values().stream().mapToInt(Integer::intValue).sum();
        return new Summary(total, byAction.getOrDefault("CREATE", 0), byAction.getOrDefault("MATCH", 0),
            byAction.getOrDefault("SKIP_DUPLICATE", 0), byAction.getOrDefault("CONFLICT", 0),
            byAction.getOrDefault("INVALID", 0), unresolved[0]);
    }

    public int setResolution(UUID importId, int rowNumber, String resolution) {
        return jdbc.sql("""
                UPDATE roster_import_row SET resolution = :r
                WHERE import_id = :i AND row_number = :n AND action = 'CONFLICT'
                """)
            .param("i", importId).param("n", rowNumber).param("r", resolution).update();
    }

    public Optional<StoredRow> row(UUID importId, int rowNumber) {
        return jdbc.sql("SELECT " + ROW_COLUMNS + " FROM roster_import_row WHERE import_id = :i AND row_number = :n")
            .param("i", importId).param("n", rowNumber).query(RosterImportRepository::mapRow).optional();
    }

    private static StoredRow mapRow(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new StoredRow(rs.getInt("row_number"), rs.getString("student_number"), rs.getString("last_name"),
            rs.getString("first_name"), rs.getString("middle_name"), rs.getString("email"), rs.getString("program"),
            rs.getString("section"), rs.getString("action"), rs.getString("conflict_kind"), rs.getString("resolution"),
            rs.getString("message"), rs.getObject("user_id", UUID.class));
    }

    /**
     * Inserts a brand-new pending student account; returns its id. No email is stored: the student supplies
     * and verifies their own address when activating, so rosters can neither squat nor probe email addresses.
     */
    public UUID insertPendingStudent(String studentNumber, String lastName, String firstName, String middleName,
                                     UUID createdBy) {
        return jdbc.sql("""
                INSERT INTO app_user (role, status, student_number, last_name, first_name, middle_name, created_by)
                VALUES ('STUDENT', 'PENDING_ACTIVATION', :sn, :ln, :fn, :mn, :by) RETURNING id
                """)
            .param("sn", studentNumber).param("ln", lastName).param("fn", firstName).param("mn", middleName)
            .param("by", createdBy).query(UUID.class).single();
    }
}
