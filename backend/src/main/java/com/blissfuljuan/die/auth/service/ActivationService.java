package com.blissfuljuan.die.auth.service;

import com.blissfuljuan.die.account.service.TempCredentialService;
import com.blissfuljuan.die.audit.service.AuditService;
import com.blissfuljuan.die.auth.view.ActivationCheckView;
import com.blissfuljuan.die.auth.view.ActivationStatusView;
import com.blissfuljuan.die.common.util.AfterCommit;
import com.blissfuljuan.die.common.util.Tokens;
import com.blissfuljuan.die.mail.service.EmailSender;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Account activation (brief 3.1): identifier + temporary credential -> email + new password ->
 * email verification -> ACTIVE (temporary credential invalidated at that point).
 *
 * Failure paths throw ResponseStatusException; these methods use noRollbackFor so that failed-attempt
 * counters and lockouts are committed even though the request is rejected.
 */
@Service
public class ActivationService {

    private record Pending(UUID userId, String firstName, String lastName, String email, boolean emailVerified,
                           UUID credentialId, String secretHash, OffsetDateTime expiresAt) {}

    private static final String INVALID = "Invalid or expired credentials";
    private static final String INVALID_LINK = "Invalid or expired verification link";

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final PasswordPolicy passwordPolicy;
    private final LoginThrottleService throttle;
    private final TempCredentialService tempCredentials;
    private final EmailSender mail;
    private final AuditService audit;
    private final int maxCredentialAttempts;
    private final int verificationHours;
    private final String frontendBaseUrl;
    private final String dummyHash;

    public ActivationService(JdbcClient jdbc, PasswordEncoder encoder, PasswordPolicy passwordPolicy,
                             LoginThrottleService throttle, TempCredentialService tempCredentials,
                             EmailSender mail, AuditService audit,
                             @Value("${die.auth.temp-credential-max-attempts:5}") int maxCredentialAttempts,
                             @Value("${die.auth.email-verification-hours:24}") int verificationHours,
                             @Value("${die.frontend-base-url:http://localhost:5173}") String frontendBaseUrl) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.passwordPolicy = passwordPolicy;
        this.throttle = throttle;
        this.tempCredentials = tempCredentials;
        this.mail = mail;
        this.audit = audit;
        this.maxCredentialAttempts = maxCredentialAttempts;
        this.verificationHours = verificationHours;
        this.frontendBaseUrl = frontendBaseUrl.replaceAll("/+$", "");
        // Burned on unknown identifiers so response time does not reveal whether an account exists.
        this.dummyHash = encoder.encode("dummy-" + UUID.randomUUID());
    }

    /** Step 4: sign in with identifier + temporary credential. Changes nothing except failure counters. */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public ActivationCheckView check(String identifier, String temporaryCredential, String clientIp) {
        Pending p = authenticate(identifier, temporaryCredential, clientIp);
        return new ActivationCheckView(p.firstName(), p.lastName(), p.email(), p.emailVerified());
    }

    /** Steps 5-6: set email + new password. Verification email is sent unless the same verified email is kept. */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public ActivationStatusView begin(String identifier, String temporaryCredential, String rawEmail,
                             String newPassword, String clientIp) {
        Pending p = authenticate(identifier, temporaryCredential, clientIp);
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        passwordPolicy.validate(newPassword, List.of(identifier, email, temporaryCredential));

        boolean keepVerified = p.emailVerified() && email.equals(p.email());
        jdbc.sql("""
                UPDATE app_user SET password_hash = :hash, email = :email,
                       email_verified_at = CASE WHEN :keep THEN email_verified_at ELSE NULL END,
                       status = CASE WHEN :keep THEN 'ACTIVE' ELSE status END,
                       updated_at = now(), version = version + 1
                WHERE id = :id
                """)
            .param("hash", encoder.encode(newPassword)).param("email", email)
            .param("keep", keepVerified).param("id", p.userId())
            .update();

        // Any earlier, unused verification link is void now (it may point at a different address).
        jdbc.sql("DELETE FROM email_verification_token WHERE user_id = :u AND consumed_at IS NULL")
            .param("u", p.userId()).update();

        // Replacing the address of an account whose email was already verified is a takeover vector
        // (e.g. a credential reset followed by activation with another address): record both addresses
        // and warn the previous owner.
        Map<String, Object> emailDetails = new HashMap<>();
        emailDetails.put("previousEmail", p.email());
        emailDetails.put("newEmail", email);
        if (p.emailVerified() && p.email() != null && !p.email().equals(email)) {
            String previous = p.email();
            String notice = "Hello " + p.firstName() + ",\n\nThe email address of your Document-Implementation "
                + "Evaluator account is being changed to a different address. If this was not you, contact "
                + "your administrator immediately.\n";
            AfterCommit.run("email change notice", () -> mail.send(previous, "Your account email is being changed", notice));
        }

        if (keepVerified) {
            tempCredentials.invalidateActive(p.userId(), "ACTIVATED");
            audit.record(p.userId(), "ACCOUNT_ACTIVATED", "user", p.userId().toString(), emailDetails);
            return new ActivationStatusView("ACTIVE");
        }

        String token = newToken();
        jdbc.sql("""
                INSERT INTO email_verification_token (user_id, email, token_hash, expires_at)
                VALUES (:u, :email, :h, now() + make_interval(hours => :hours))
                """)
            .param("u", p.userId()).param("email", email).param("h", sha256(token))
            .param("hours", verificationHours).update();
        audit.record(p.userId(), "ACTIVATION_STARTED", "user", p.userId().toString(), emailDetails);

        String link = frontendBaseUrl + "/verify-email?token=" + token;
        String body = "Hello " + p.firstName() + ",\n\nConfirm your email address to finish activating your "
            + "Document-Implementation Evaluator account:\n\n" + link + "\n\nThis link expires in "
            + verificationHours + " hours. If you did not request this, ignore this message.\n";
        AfterCommit.run("activation email", () -> mail.send(email, "Confirm your email address", body));
        return new ActivationStatusView("VERIFICATION_REQUIRED");
    }

    /** Steps 6-7: email ownership proven -> account ACTIVE, temporary credential invalidated. */
    @Transactional
    public ActivationStatusView verifyEmail(String token) {
        var row = jdbc.sql("""
                SELECT t.id AS token_id, t.user_id, t.email AS token_email, u.email AS user_email,
                       u.status, (u.password_hash IS NOT NULL) AS has_password
                FROM email_verification_token t JOIN app_user u ON u.id = t.user_id
                WHERE t.token_hash = :h AND t.consumed_at IS NULL AND t.expires_at > now()
                FOR UPDATE OF t, u
                """)
            .param("h", sha256(token))
            .query((rs, n) -> Map.<String, Object>of(
                "tokenId", rs.getObject("token_id", UUID.class),
                "userId", rs.getObject("user_id", UUID.class),
                "tokenEmail", rs.getString("token_email"),
                "userEmail", String.valueOf(rs.getString("user_email")),
                "status", rs.getString("status"),
                "hasPassword", rs.getBoolean("has_password")))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, INVALID_LINK));

        UUID userId = (UUID) row.get("userId");
        boolean consistent = row.get("tokenEmail").equals(row.get("userEmail"))
            && "PENDING_ACTIVATION".equals(row.get("status"))
            && (boolean) row.get("hasPassword");
        if (!consistent) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, INVALID_LINK);
        }
        jdbc.sql("UPDATE email_verification_token SET consumed_at = now() WHERE id = :id")
            .param("id", row.get("tokenId")).update();
        jdbc.sql("""
                UPDATE app_user SET email_verified_at = now(), status = 'ACTIVE',
                       updated_at = now(), version = version + 1
                WHERE id = :id
                """)
            .param("id", userId).update();
        tempCredentials.invalidateActive(userId, "ACTIVATED");
        audit.record(userId, "ACCOUNT_ACTIVATED", "user", userId.toString(),
            Map.of("emailVerified", true, "email", String.valueOf(row.get("userEmail"))));
        return new ActivationStatusView("ACTIVE");
    }

    // ---- internals ----

    /**
     * Verifies identifier + temporary credential. Every failure mode (unknown account, wrong secret,
     * expired, locked) yields the same 401. Wrong secrets are counted per credential (lockout) and
     * per identifier/IP (throttle).
     */
    private Pending authenticate(String identifier, String secret, String clientIp) {
        String id = identifier.trim().toLowerCase(Locale.ROOT);
        String throttleKey = "act:" + id;
        throttle.assertAllowed(throttleKey, clientIp);

        Optional<Pending> found = find(id);
        boolean matches = encoder.matches(secret, found.map(Pending::secretHash).orElse(dummyHash));

        if (found.isEmpty() || !matches) {
            found.ifPresent(p -> recordCredentialFailure(p));
            throttle.record(throttleKey, clientIp, false);
            audit.record(found.map(Pending::userId).orElse(null), "ACTIVATION_FAILED", "auth", null, Map.of());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, INVALID);
        }
        Pending p = found.get();
        if (p.expiresAt().isBefore(OffsetDateTime.now())) {
            tempCredentials.invalidateActive(p.userId(), "EXPIRED");
            throttle.record(throttleKey, clientIp, false);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, INVALID);
        }
        return p;
    }

    private Optional<Pending> find(String id) {
        String where = id.contains("@") ? "lower(u.email) = :id" : "lower(u.student_number) = :id";
        String sql = """
            SELECT u.id, u.first_name, u.last_name, u.email, (u.email_verified_at IS NOT NULL) AS ev,
                   c.id AS cid, c.secret_hash, c.expires_at
            FROM app_user u JOIN temp_credential c ON c.user_id = u.id AND c.invalidated_at IS NULL
            WHERE u.status = 'PENDING_ACTIVATION'
            """ + " AND " + where + " FOR UPDATE OF c";
        return jdbc.sql(sql)
            .param("id", id)
            .query((rs, n) -> new Pending(
                rs.getObject("id", UUID.class), rs.getString("first_name"), rs.getString("last_name"),
                rs.getString("email"), rs.getBoolean("ev"), rs.getObject("cid", UUID.class),
                rs.getString("secret_hash"), rs.getObject("expires_at", OffsetDateTime.class)))
            .optional();
    }

    private void recordCredentialFailure(Pending p) {
        int attempts = jdbc.sql("""
                UPDATE temp_credential SET failed_attempts = failed_attempts + 1 WHERE id = :id
                RETURNING failed_attempts
                """)
            .param("id", p.credentialId()).query(Integer.class).single();
        if (attempts >= maxCredentialAttempts) {
            tempCredentials.invalidateActive(p.userId(), "LOCKED");
            audit.record(p.userId(), "TEMP_CREDENTIAL_LOCKED", "user", p.userId().toString(), Map.of());
        }
    }

    private static String newToken() {
        return Tokens.newToken();
    }

    private static String sha256(String value) {
        return Tokens.sha256(value);
    }
}
