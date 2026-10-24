package com.blissfuljuan.dieapi.roster;

import com.blissfuljuan.dieapi.PostgresIntegrationTest;
import com.blissfuljuan.dieapi.roster.service.RosterImportPurger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Uncommitted roster imports (and the personal data in their rows) are purged; committed ones are kept. */
class RosterImportPurgeIntegrationTest extends PostgresIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired RosterImportPurger purger;
    @Autowired PasswordEncoder encoder;

    UUID workspace, uploader;

    @BeforeEach
    void seed() {
        uploader = jdbc.queryForObject("""
            INSERT INTO app_user (role, status, last_name, first_name, email, email_verified_at, password_hash)
            VALUES ('COORDINATOR', 'ACTIVE', 'Reyes', 'Ana', 'ana@example.edu', now(), ?) RETURNING id
            """, UUID.class, encoder.encode("correct horse battery staple"));
        workspace = jdbc.queryForObject("""
            WITH c AS (INSERT INTO academic_class (academic_year, semester, subject_code, subject_name, section, owner_id)
            VALUES ('2025-2026', '1st', 'CS101', 'Subject', 'A', ?) RETURNING id)
            INSERT INTO class_instructor (class_id, user_id) SELECT id, ? FROM c RETURNING class_id
            """, UUID.class, uploader, uploader);
    }

    private UUID roster(String status, String age) {
        UUID id = jdbc.queryForObject("""
            INSERT INTO roster_import (class_id, uploaded_by, file_name, file_sha256, status, created_at)
            VALUES (?, ?, 'r.csv', 'x', ?, now() - ?::interval) RETURNING id
            """, UUID.class, workspace, uploader, status, age);
        jdbc.update("""
            INSERT INTO roster_import_row (import_id, row_number, student_number, last_name, first_name, action)
            VALUES (?, 1, '2024-0001', 'Cruz', 'Maria', 'CREATE')
            """, id);
        return id;
    }

    private boolean exists(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM roster_import WHERE id = ?", Integer.class, id) == 1;
    }

    @Test
    void oldUncommittedImportsAreDeletedWithTheirRowsAndEverythingElseStays() {
        UUID oldPreview = roster("PREVIEW", "8 days");
        UUID oldCancelled = roster("CANCELLED", "30 days");
        UUID freshPreview = roster("PREVIEW", "1 day");
        UUID oldCommitted = roster("COMMITTED", "30 days");
        jdbc.update("UPDATE roster_import SET committed_at = now() WHERE id = ?", oldCommitted);

        assertThat(purger.purgeOnce()).isEqualTo(2);

        assertThat(exists(oldPreview)).isFalse();
        assertThat(exists(oldCancelled)).isFalse();
        assertThat(exists(freshPreview)).isTrue();
        assertThat(exists(oldCommitted)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM roster_import_row", Integer.class)).isEqualTo(2);
    }
}
