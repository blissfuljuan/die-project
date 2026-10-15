package io.die.evaluator.account;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    /** Row state needed for guarded updates (loaded with FOR UPDATE). */
    public record Locked(UUID id, String role, String status, boolean hasPassword, boolean emailVerified,
                         long version, String studentNumber, String lastName, String firstName,
                         String middleName, String email) {}

    public record PageResult(List<UserView> items, int page, int size, long total) {}

    private static final String VIEW_COLUMNS = """
        id, role, status, student_number, last_name, first_name, middle_name, email,
        (email_verified_at IS NOT NULL) AS email_verified, created_at, version
        """;

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static UserView mapView(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new UserView(
            rs.getObject("id", UUID.class), rs.getString("role"), rs.getString("status"),
            rs.getString("student_number"), rs.getString("last_name"), rs.getString("first_name"),
            rs.getString("middle_name"), rs.getString("email"), rs.getBoolean("email_verified"),
            rs.getObject("created_at", java.time.OffsetDateTime.class), rs.getLong("version"));
    }

    public Optional<UserView> find(UUID id) {
        return jdbc.sql("SELECT " + VIEW_COLUMNS + " FROM app_user WHERE id = :id")
            .param("id", id).query(AccountRepository::mapView).optional();
    }

    public Optional<Locked> lock(UUID id) {
        return jdbc.sql("""
                SELECT id, role, status, (password_hash IS NOT NULL) AS has_password,
                       (email_verified_at IS NOT NULL) AS email_verified, version, student_number,
                       last_name, first_name, middle_name, email
                FROM app_user WHERE id = :id FOR UPDATE
                """)
            .param("id", id)
            .query((rs, n) -> new Locked(
                rs.getObject("id", UUID.class), rs.getString("role"), rs.getString("status"),
                rs.getBoolean("has_password"), rs.getBoolean("email_verified"), rs.getLong("version"),
                rs.getString("student_number"), rs.getString("last_name"), rs.getString("first_name"),
                rs.getString("middle_name"), rs.getString("email")))
            .optional();
    }

    public UUID insert(String role, String studentNumber, String lastName, String firstName,
                       String middleName, String email, UUID createdBy) {
        return jdbc.sql("""
                INSERT INTO app_user (role, status, student_number, last_name, first_name, middle_name, email, created_by)
                VALUES (:role, 'PENDING_ACTIVATION', :sn, :ln, :fn, :mn, :email, :by)
                RETURNING id
                """)
            .param("role", role).param("sn", studentNumber).param("ln", lastName)
            .param("fn", firstName).param("mn", middleName).param("email", email).param("by", createdBy)
            .query(UUID.class).single();
    }

    public void updateProfile(UUID id, String role, String studentNumber, String lastName, String firstName,
                              String middleName, String email) {
        jdbc.sql("""
                UPDATE app_user SET role = :role, student_number = :sn, last_name = :ln, first_name = :fn,
                       middle_name = :mn, email = :email, updated_at = now(), version = version + 1
                WHERE id = :id
                """)
            .param("id", id).param("role", role).param("sn", studentNumber).param("ln", lastName)
            .param("fn", firstName).param("mn", middleName).param("email", email)
            .update();
    }

    public void setStatus(UUID id, String status) {
        jdbc.sql("UPDATE app_user SET status = :s, updated_at = now(), version = version + 1 WHERE id = :id")
            .param("id", id).param("s", status).update();
    }

    /** Credential reset: no usable password until the user re-activates with a new temporary credential. */
    public void clearPasswordAndSetPending(UUID id) {
        jdbc.sql("""
                UPDATE app_user SET password_hash = NULL, status = 'PENDING_ACTIVATION',
                       updated_at = now(), version = version + 1
                WHERE id = :id
                """)
            .param("id", id).update();
    }

    public PageResult search(String role, String status, String q, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        Map<String, Object> params = new HashMap<>();
        if (role != null) { where.append(" AND role = :role"); params.put("role", role); }
        if (status != null) { where.append(" AND status = :status"); params.put("status", status); }
        if (q != null && !q.isBlank()) {
            String escaped = q.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            where.append("""
                 AND lower(last_name || ' ' || first_name || ' ' || coalesce(email, '') || ' ' ||
                           coalesce(student_number, '')) LIKE :q ESCAPE '\\'
                """);
            params.put("q", "%" + escaped + "%");
        }
        long total = jdbc.sql("SELECT count(*) FROM app_user" + where).params(params)
            .query(Long.class).single();
        List<UserView> items = new ArrayList<>(jdbc.sql(
                "SELECT " + VIEW_COLUMNS + " FROM app_user" + where
                    + " ORDER BY last_name, first_name, id LIMIT :limit OFFSET :offset")
            .params(params).param("limit", size).param("offset", (long) page * size)
            .query(AccountRepository::mapView).list());
        return new PageResult(items, page, size, total);
    }
}
