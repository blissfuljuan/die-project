package com.blissfuljuan.dieapi.auth.service;

import com.blissfuljuan.dieapi.account.service.SessionRevocationService;
import com.blissfuljuan.dieapi.audit.service.AuditService;
import com.blissfuljuan.dieapi.common.util.AfterCommit;
import com.blissfuljuan.dieapi.common.util.Tokens;
import com.blissfuljuan.dieapi.mail.service.EmailSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Password recovery (emailed single-use token) and authenticated password change.
 * Every successful change revokes the user's other sessions.
 */
@Service
public class PasswordService {

    private record Account(UUID id, String email, String firstName) {}

    private static final String INVALID_LINK = "Invalid or expired reset link";

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;
    private final LoginThrottleService throttle;
    private final SessionRevocationService sessions;
    private final EmailSender mail;
    private final AuditService audit;
    private final int resetMinutes;
    private final String frontendBaseUrl;

    public PasswordService(JdbcClient jdbc, PasswordEncoder encoder, PasswordPolicy policy,
                           LoginThrottleService throttle, SessionRevocationService sessions,
                           EmailSender mail, AuditService audit,
                           @Value("${die.auth.password-reset-minutes:60}") int resetMinutes,
                           @Value("${die.frontend-base-url:http://localhost:5173}") String frontendBaseUrl) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.policy = policy;
        this.throttle = throttle;
        this.sessions = sessions;
        this.mail = mail;
        this.audit = audit;
        this.resetMinutes = resetMinutes;
        this.frontendBaseUrl = frontendBaseUrl.replaceAll("/+$", "");
    }

    /**
     * Always completes identically whether or not the address belongs to an account (no enumeration).
     * Every request counts toward the throttle, so repeated requests get 429 for any address alike.
     */
    @Transactional
    public void requestReset(String rawEmail, String clientIp) {
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        String key = LoginThrottleService.RESET_PREFIX + email;
        throttle.assertResetAllowed(key, clientIp);
        throttle.record(key, clientIp, false);

        Optional<Account> account = jdbc.sql("""
                SELECT id, email, first_name FROM app_user
                WHERE lower(email) = :email AND status = 'ACTIVE' AND email_verified_at IS NOT NULL
                """)
            .param("email", email)
            .query((rs, n) -> new Account(rs.getObject("id", UUID.class), rs.getString("email"),
                rs.getString("first_name")))
            .optional();
        if (account.isEmpty()) {
            return;
        }
        Account a = account.get();
        // A new request voids older unused links.
        jdbc.sql("DELETE FROM password_reset_token WHERE user_id = :u AND consumed_at IS NULL")
            .param("u", a.id()).update();
        String token = Tokens.newToken();
        jdbc.sql("""
                INSERT INTO password_reset_token (user_id, token_hash, expires_at)
                VALUES (:u, :h, now() + make_interval(mins => :m))
                """)
            .param("u", a.id()).param("h", Tokens.sha256(token)).param("m", resetMinutes).update();
        audit.record(a.id(), "PASSWORD_RESET_REQUESTED", "user", a.id().toString(), Map.of());

        String body = "Hello " + a.firstName() + ",\n\nSomeone asked to reset the password of your "
            + "Document-Implementation Evaluator account. To choose a new password open:\n\n"
            + frontendBaseUrl + "/reset-password?token=" + token + "\n\nThis link expires in " + resetMinutes
            + " minutes and works once. If you did not ask for this, you can ignore this message; "
            + "your password has not changed.\n";
        AfterCommit.run("password reset email", () -> mail.send(a.email(), "Reset your password", body));
    }

    /** Completes a reset: new password, all sessions revoked, login lockout cleared (ownership of the email is proven). */
    @Transactional
    public void confirmReset(String token, String newPassword, String clientIp) {
        record Row(UUID tokenId, UUID userId, String email, String firstName) {}
        Row row = jdbc.sql("""
                SELECT t.id AS token_id, u.id AS user_id, u.email, u.first_name
                FROM password_reset_token t JOIN app_user u ON u.id = t.user_id
                WHERE t.token_hash = :h AND t.consumed_at IS NULL AND t.expires_at > now()
                  AND u.status = 'ACTIVE'
                FOR UPDATE OF t, u
                """)
            .param("h", Tokens.sha256(token))
            .query((rs, n) -> new Row(rs.getObject("token_id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getString("email"), rs.getString("first_name")))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, INVALID_LINK));

        policy.validate(newPassword, List.of(row.email())); // rolls back: the link stays usable for a retry

        setPassword(row.userId(), newPassword);
        jdbc.sql("UPDATE password_reset_token SET consumed_at = now() WHERE id = :id")
            .param("id", row.tokenId()).update();
        jdbc.sql("DELETE FROM password_reset_token WHERE user_id = :u AND consumed_at IS NULL")
            .param("u", row.userId()).update();
        sessions.revokeAllAfterCommit(row.userId());
        throttle.record(row.email().toLowerCase(Locale.ROOT), clientIp, true);
        audit.record(row.userId(), "PASSWORD_RESET_COMPLETED", "user", row.userId().toString(), Map.of());
        sendChangedNotice(row.email(), row.firstName());
    }

    /** Authenticated change. Other sessions are revoked; the calling session stays signed in. */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public void change(UUID userId, String currentSessionId, String currentPassword, String newPassword,
                       String clientIp) {
        String key = "pwchange:" + userId;
        throttle.assertAllowed(key, clientIp);

        var row = jdbc.sql("SELECT email, first_name, password_hash FROM app_user WHERE id = :id FOR UPDATE")
            .param("id", userId)
            .query((rs, n) -> Map.of("email", rs.getString("email"), "first", rs.getString("first_name"),
                "hash", rs.getString("password_hash")))
            .single();

        if (!encoder.matches(currentPassword, row.get("hash"))) {
            throttle.record(key, clientIp, false);
            audit.record(userId, "PASSWORD_CHANGE_FAILED", "user", userId.toString(), Map.of());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
        }
        policy.validate(newPassword, List.of(row.get("email"), currentPassword));

        setPassword(userId, newPassword);
        sessions.revokeAllExceptAfterCommit(userId, currentSessionId);
        throttle.record(key, clientIp, true);
        audit.record(userId, "PASSWORD_CHANGED", "user", userId.toString(), Map.of());
        sendChangedNotice(row.get("email"), row.get("first"));
    }

    private void setPassword(UUID userId, String newPassword) {
        jdbc.sql("UPDATE app_user SET password_hash = :h, updated_at = now(), version = version + 1 WHERE id = :id")
            .param("h", encoder.encode(newPassword)).param("id", userId).update();
    }

    private void sendChangedNotice(String email, String firstName) {
        String body = "Hello " + firstName + ",\n\nThe password of your Document-Implementation Evaluator "
            + "account was just changed and you were signed out of your other sessions. If this was not "
            + "you, reset your password immediately and contact your administrator.\n";
        AfterCommit.run("password changed notice", () -> mail.send(email, "Your password was changed", body));
    }
}
