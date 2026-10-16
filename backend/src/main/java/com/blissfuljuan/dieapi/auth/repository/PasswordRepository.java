package com.blissfuljuan.dieapi.auth.repository;

import com.blissfuljuan.dieapi.auth.model.PasswordAccount;
import com.blissfuljuan.dieapi.auth.model.PasswordAccountSecret;
import com.blissfuljuan.dieapi.auth.model.ResetLink;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** Persistence for password recovery (password_reset_token) and password changes on app_user. */
@Repository
public class PasswordRepository {

    private final JdbcClient jdbc;

    public PasswordRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** An active, email-verified account by lower-cased address. */
    public Optional<PasswordAccount> findActiveByEmail(String email) {
        return jdbc.sql("""
                SELECT id, email, first_name FROM app_user
                WHERE lower(email) = :email AND status = 'ACTIVE' AND email_verified_at IS NOT NULL
                """)
            .param("email", email)
            .query((rs, n) -> new PasswordAccount(rs.getObject("id", UUID.class), rs.getString("email"),
                rs.getString("first_name")))
            .optional();
    }

    public void deleteUnusedResetTokens(UUID userId) {
        jdbc.sql("DELETE FROM password_reset_token WHERE user_id = :u AND consumed_at IS NULL")
            .param("u", userId).update();
    }

    public void insertResetToken(UUID userId, String tokenHash, int minutes) {
        jdbc.sql("""
                INSERT INTO password_reset_token (user_id, token_hash, expires_at)
                VALUES (:u, :h, now() + make_interval(mins => :m))
                """)
            .param("u", userId).param("h", tokenHash).param("m", minutes).update();
    }

    /** A live token of an ACTIVE user, both rows locked. */
    public Optional<ResetLink> lockLiveReset(String tokenHash) {
        return jdbc.sql("""
                SELECT t.id AS token_id, u.id AS user_id, u.email, u.first_name
                FROM password_reset_token t JOIN app_user u ON u.id = t.user_id
                WHERE t.token_hash = :h AND t.consumed_at IS NULL AND t.expires_at > now()
                  AND u.status = 'ACTIVE'
                FOR UPDATE OF t, u
                """)
            .param("h", tokenHash)
            .query((rs, n) -> new ResetLink(rs.getObject("token_id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getString("email"), rs.getString("first_name")))
            .optional();
    }

    public void consumeResetToken(UUID tokenId) {
        jdbc.sql("UPDATE password_reset_token SET consumed_at = now() WHERE id = :id")
            .param("id", tokenId).update();
    }

    /** Locks the user row and returns what a password change needs. */
    public PasswordAccountSecret lockForChange(UUID userId) {
        return jdbc.sql("SELECT email, first_name, password_hash FROM app_user WHERE id = :id FOR UPDATE")
            .param("id", userId)
            .query((rs, n) -> new PasswordAccountSecret(rs.getString("email"), rs.getString("first_name"),
                rs.getString("password_hash")))
            .single();
    }

    public void setPasswordHash(UUID userId, String hash) {
        jdbc.sql("UPDATE app_user SET password_hash = :h, updated_at = now(), version = version + 1 WHERE id = :id")
            .param("h", hash).param("id", userId).update();
    }
}
