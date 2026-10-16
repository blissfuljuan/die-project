package com.blissfuljuan.dieapi.auth.repository;

import com.blissfuljuan.dieapi.auth.model.PendingActivation;
import com.blissfuljuan.dieapi.auth.model.VerificationLink;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** Persistence for account activation: pending accounts, and email_verification_token. */
@Repository
public class ActivationRepository {

    private final JdbcClient jdbc;

    public ActivationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Finds the pending account by lower-cased email (when the identifier has an '@') or student number; locks its credential. */
    public Optional<PendingActivation> findPending(String id) {
        String where = id.contains("@") ? "lower(u.email) = :id" : "lower(u.student_number) = :id";
        String sql = """
            SELECT u.id, u.first_name, u.last_name, u.email, (u.email_verified_at IS NOT NULL) AS ev,
                   c.id AS cid, c.secret_hash, c.expires_at
            FROM app_user u JOIN temp_credential c ON c.user_id = u.id AND c.invalidated_at IS NULL
            WHERE u.status = 'PENDING_ACTIVATION'
            """ + " AND " + where + " FOR UPDATE OF c";
        return jdbc.sql(sql)
            .param("id", id)
            .query((rs, n) -> new PendingActivation(
                rs.getObject("id", UUID.class), rs.getString("first_name"), rs.getString("last_name"),
                rs.getString("email"), rs.getBoolean("ev"), rs.getObject("cid", UUID.class),
                rs.getString("secret_hash"), rs.getObject("expires_at", OffsetDateTime.class)))
            .optional();
    }

    /** Sets the chosen password and email; keeps verification (and activates) only when {@code keepVerified}. */
    public void applyChosenCredentials(UUID userId, String passwordHash, String email, boolean keepVerified) {
        jdbc.sql("""
                UPDATE app_user SET password_hash = :hash, email = :email,
                       email_verified_at = CASE WHEN :keep THEN email_verified_at ELSE NULL END,
                       status = CASE WHEN :keep THEN 'ACTIVE' ELSE status END,
                       updated_at = now(), version = version + 1
                WHERE id = :id
                """)
            .param("hash", passwordHash).param("email", email)
            .param("keep", keepVerified).param("id", userId)
            .update();
    }

    public void deleteUnusedVerificationTokens(UUID userId) {
        jdbc.sql("DELETE FROM email_verification_token WHERE user_id = :u AND consumed_at IS NULL")
            .param("u", userId).update();
    }

    public void insertVerificationToken(UUID userId, String email, String tokenHash, int hours) {
        jdbc.sql("""
                INSERT INTO email_verification_token (user_id, email, token_hash, expires_at)
                VALUES (:u, :email, :h, now() + make_interval(hours => :hours))
                """)
            .param("u", userId).param("email", email).param("h", tokenHash)
            .param("hours", hours).update();
    }

    /** A live token with its user, both rows locked. */
    public Optional<VerificationLink> lockLiveVerification(String tokenHash) {
        return jdbc.sql("""
                SELECT t.id AS token_id, t.user_id, t.email AS token_email, u.email AS user_email,
                       u.status, (u.password_hash IS NOT NULL) AS has_password
                FROM email_verification_token t JOIN app_user u ON u.id = t.user_id
                WHERE t.token_hash = :h AND t.consumed_at IS NULL AND t.expires_at > now()
                FOR UPDATE OF t, u
                """)
            .param("h", tokenHash)
            .query((rs, n) -> new VerificationLink(
                rs.getObject("token_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("token_email"),
                String.valueOf(rs.getString("user_email")),
                rs.getString("status"),
                rs.getBoolean("has_password")))
            .optional();
    }

    public void consumeVerificationToken(UUID tokenId) {
        jdbc.sql("UPDATE email_verification_token SET consumed_at = now() WHERE id = :id")
            .param("id", tokenId).update();
    }

    public void markEmailVerifiedAndActive(UUID userId) {
        jdbc.sql("""
                UPDATE app_user SET email_verified_at = now(), status = 'ACTIVE',
                       updated_at = now(), version = version + 1
                WHERE id = :id
                """)
            .param("id", userId).update();
    }
}
