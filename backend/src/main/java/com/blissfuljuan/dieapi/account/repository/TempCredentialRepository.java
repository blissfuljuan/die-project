package com.blissfuljuan.dieapi.account.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Persistence of temp_credential (hashes only). */
@Repository
public class TempCredentialRepository {

    private final JdbcClient jdbc;

    public TempCredentialRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void invalidateActive(UUID userId, String reason) {
        jdbc.sql("""
                UPDATE temp_credential SET invalidated_at = now(), invalid_reason = :r
                WHERE user_id = :u AND invalidated_at IS NULL
                """)
            .param("u", userId).param("r", reason).update();
    }

    /** Inserts a credential valid for {@code validDays} days and returns its expiry. */
    public OffsetDateTime insert(UUID userId, String hash, int validDays, UUID createdBy) {
        return jdbc.sql("""
                INSERT INTO temp_credential (user_id, secret_hash, expires_at, created_by)
                VALUES (:u, :h, now() + make_interval(days => :d), :by)
                RETURNING expires_at
                """)
            .param("u", userId).param("h", hash).param("d", validDays).param("by", createdBy)
            .query((rs, n) -> rs.getObject("expires_at", OffsetDateTime.class)).single();
    }

    /** Counts a wrong guess against one credential and returns the new total. */
    public int incrementFailedAttempts(UUID credentialId) {
        return jdbc.sql("""
                UPDATE temp_credential SET failed_attempts = failed_attempts + 1 WHERE id = :id
                RETURNING failed_attempts
                """)
            .param("id", credentialId).query(Integer.class).single();
    }
}
