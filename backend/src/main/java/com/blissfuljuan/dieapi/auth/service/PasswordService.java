package com.blissfuljuan.dieapi.auth.service;

import com.blissfuljuan.dieapi.account.service.SessionRevocationService;
import com.blissfuljuan.dieapi.audit.model.AuditAction;
import com.blissfuljuan.dieapi.audit.model.AuditEvent;
import com.blissfuljuan.dieapi.audit.model.AuditResource;
import com.blissfuljuan.dieapi.auth.model.PasswordAccount;
import com.blissfuljuan.dieapi.auth.model.PasswordAccountSecret;
import com.blissfuljuan.dieapi.auth.model.ResetLink;
import com.blissfuljuan.dieapi.auth.model.rule.ThrottleBucket;
import com.blissfuljuan.dieapi.auth.repository.PasswordRepository;
import org.springframework.context.ApplicationEventPublisher;
import com.blissfuljuan.dieapi.common.util.Tokens;
import com.blissfuljuan.dieapi.mail.service.MailQueue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.blissfuljuan.dieapi.common.exception.ApiException;
import com.blissfuljuan.dieapi.common.exception.ErrorCode;

import java.util.*;

/**
 * Password recovery (emailed single-use token) and authenticated password change.
 * Every successful change revokes the user's other sessions.
 */
@Service
public class PasswordService {

    private static final String INVALID_LINK = "Invalid or expired reset link";

    private final PasswordRepository repository;
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;
    private final LoginThrottleService throttle;
    private final SessionRevocationService sessions;
    private final MailQueue mail;
    private final ApplicationEventPublisher events;
    private final int resetMinutes;
    private final String frontendBaseUrl;

    public PasswordService(PasswordRepository repository, PasswordEncoder encoder, PasswordPolicy policy,
                           LoginThrottleService throttle, SessionRevocationService sessions,
                           MailQueue mail, ApplicationEventPublisher events,
                           @Value("${die.auth.password-reset-minutes:60}") int resetMinutes,
                           @Value("${die.frontend-base-url:http://localhost:5173}") String frontendBaseUrl) {
        this.repository = repository;
        this.encoder = encoder;
        this.policy = policy;
        this.throttle = throttle;
        this.sessions = sessions;
        this.mail = mail;
        this.events = events;
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
        String key = ThrottleBucket.resetKey(email);
        throttle.assertResetAllowed(key, clientIp);
        throttle.record(key, clientIp, false);

        Optional<PasswordAccount> account = repository.findActiveByEmail(email);
        if (account.isEmpty()) {
            return;
        }
        PasswordAccount a = account.get();
        // A new request voids older unused links.
        repository.deleteUnusedResetTokens(a.id());
        String token = Tokens.newToken();
        repository.insertResetToken(a.id(), Tokens.sha256(token), resetMinutes);
        events.publishEvent(AuditEvent.of(a.id(), AuditAction.PASSWORD_RESET_REQUESTED, AuditResource.USER, a.id().toString(), Map.of()));

        String body = "Hello " + a.firstName() + ",\n\nSomeone asked to reset the password of your "
            + "Document-Implementation Evaluator account. To choose a new password open:\n\n"
            + frontendBaseUrl + "/reset-password?token=" + token + "\n\nThis link expires in " + resetMinutes
            + " minutes and works once. If you did not ask for this, you can ignore this message; "
            + "your password has not changed.\n";
        mail.enqueue(a.email(), "Reset your password", body);
    }

    /** Completes a reset: new password, all sessions revoked, login lockout cleared (ownership of the email is proven). */
    @Transactional
    public void confirmReset(String token, String newPassword, String clientIp) {
        ResetLink row = repository.lockLiveReset(Tokens.sha256(token))
            .orElseThrow(() -> ApiException.badRequest(ErrorCode.RESET_LINK_INVALID, INVALID_LINK));

        policy.validate(newPassword, List.of(row.email())); // rolls back: the link stays usable for a retry

        setPassword(row.userId(), newPassword);
        repository.consumeResetToken(row.tokenId());
        repository.deleteUnusedResetTokens(row.userId());
        sessions.revokeAllAfterCommit(row.userId());
        throttle.record(row.email().toLowerCase(Locale.ROOT), clientIp, true);
        events.publishEvent(AuditEvent.of(row.userId(), AuditAction.PASSWORD_RESET_COMPLETED, AuditResource.USER, row.userId().toString(), Map.of()));
        sendChangedNotice(row.email(), row.firstName());
    }

    /** Authenticated change. Other sessions are revoked; the calling session stays signed in. */
    @Transactional(noRollbackFor = ApiException.class)
    public void change(UUID userId, String currentSessionId, String currentPassword, String newPassword,
                       String clientIp) {
        String key = "pwchange:" + userId;
        throttle.assertAllowed(key, clientIp);

        PasswordAccountSecret row = repository.lockForChange(userId);

        if (!encoder.matches(currentPassword, row.passwordHash())) {
            throttle.record(key, clientIp, false);
            events.publishEvent(AuditEvent.of(userId, AuditAction.PASSWORD_CHANGE_FAILED, AuditResource.USER, userId.toString(), Map.of()));
            throw ApiException.badRequest(ErrorCode.CURRENT_PASSWORD_INCORRECT, "Current password is incorrect");
        }
        policy.validate(newPassword, List.of(row.email(), currentPassword));

        setPassword(userId, newPassword);
        sessions.revokeAllExceptAfterCommit(userId, currentSessionId);
        throttle.record(key, clientIp, true);
        events.publishEvent(AuditEvent.of(userId, AuditAction.PASSWORD_CHANGED, AuditResource.USER, userId.toString(), Map.of()));
        sendChangedNotice(row.email(), row.firstName());
    }

    private void setPassword(UUID userId, String newPassword) {
        repository.setPasswordHash(userId, encoder.encode(newPassword));
    }

    private void sendChangedNotice(String email, String firstName) {
        String body = "Hello " + firstName + ",\n\nThe password of your Document-Implementation Evaluator "
            + "account was just changed and you were signed out of your other sessions. If this was not "
            + "you, reset your password immediately and contact your administrator.\n";
        mail.enqueue(email, "Your password was changed", body);
    }
}
