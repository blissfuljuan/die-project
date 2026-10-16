package com.blissfuljuan.dieapi.auth.service;

import com.blissfuljuan.dieapi.account.model.AccountStatus;
import com.blissfuljuan.dieapi.account.service.TempCredentialService;
import com.blissfuljuan.dieapi.audit.model.AuditAction;
import com.blissfuljuan.dieapi.audit.model.AuditEvent;
import com.blissfuljuan.dieapi.audit.model.AuditResource;
import org.springframework.context.ApplicationEventPublisher;
import com.blissfuljuan.dieapi.account.repository.TempCredentialRepository;
import com.blissfuljuan.dieapi.auth.model.PendingActivation;
import com.blissfuljuan.dieapi.auth.model.VerificationLink;
import com.blissfuljuan.dieapi.auth.repository.ActivationRepository;
import com.blissfuljuan.dieapi.auth.view.ActivationCheckView;
import com.blissfuljuan.dieapi.auth.view.ActivationStatusView;
import com.blissfuljuan.dieapi.common.util.AfterCommit;
import com.blissfuljuan.dieapi.common.util.Tokens;
import com.blissfuljuan.dieapi.mail.service.EmailSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.blissfuljuan.dieapi.common.exception.ApiException;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Account activation (brief 3.1): identifier + temporary credential -> email + new password ->
 * email verification -> ACTIVE (temporary credential invalidated at that point).
 *
 * Failure paths throw ApiException; these methods use noRollbackFor so that failed-attempt
 * counters and lockouts are committed even though the request is rejected.
 */
@Service
public class ActivationService {

    private static final String INVALID = "Invalid or expired credentials";
    private static final String INVALID_LINK = "Invalid or expired verification link";

    private final ActivationRepository repository;
    private final TempCredentialRepository credentials;
    private final PasswordEncoder encoder;
    private final PasswordPolicy passwordPolicy;
    private final LoginThrottleService throttle;
    private final TempCredentialService tempCredentials;
    private final EmailSender mail;
    private final ApplicationEventPublisher events;
    private final int maxCredentialAttempts;
    private final int verificationHours;
    private final String frontendBaseUrl;
    private final String dummyHash;

    public ActivationService(ActivationRepository repository, TempCredentialRepository credentials, PasswordEncoder encoder, PasswordPolicy passwordPolicy,
                             LoginThrottleService throttle, TempCredentialService tempCredentials,
                             EmailSender mail, ApplicationEventPublisher events,
                             @Value("${die.auth.temp-credential-max-attempts:5}") int maxCredentialAttempts,
                             @Value("${die.auth.email-verification-hours:24}") int verificationHours,
                             @Value("${die.frontend-base-url:http://localhost:5173}") String frontendBaseUrl) {
        this.repository = repository;
        this.credentials = credentials;
        this.encoder = encoder;
        this.passwordPolicy = passwordPolicy;
        this.throttle = throttle;
        this.tempCredentials = tempCredentials;
        this.mail = mail;
        this.events = events;
        this.maxCredentialAttempts = maxCredentialAttempts;
        this.verificationHours = verificationHours;
        this.frontendBaseUrl = frontendBaseUrl.replaceAll("/+$", "");
        // Burned on unknown identifiers so response time does not reveal whether an account exists.
        this.dummyHash = encoder.encode("dummy-" + UUID.randomUUID());
    }

    /** Step 4: sign in with identifier + temporary credential. Changes nothing except failure counters. */
    @Transactional(noRollbackFor = ApiException.class)
    public ActivationCheckView check(String identifier, String temporaryCredential, String clientIp) {
        PendingActivation p = authenticate(identifier, temporaryCredential, clientIp);
        return new ActivationCheckView(p.firstName(), p.lastName(), p.email(), p.emailVerified());
    }

    /** Steps 5-6: set email + new password. Verification email is sent unless the same verified email is kept. */
    @Transactional(noRollbackFor = ApiException.class)
    public ActivationStatusView begin(String identifier, String temporaryCredential, String rawEmail,
                             String newPassword, String clientIp) {
        PendingActivation p = authenticate(identifier, temporaryCredential, clientIp);
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        passwordPolicy.validate(newPassword, List.of(identifier, email, temporaryCredential));

        boolean keepVerified = p.emailVerified() && email.equals(p.email());
        repository.applyChosenCredentials(p.userId(), encoder.encode(newPassword), email, keepVerified);

        // Any earlier, unused verification link is void now (it may point at a different address).
        repository.deleteUnusedVerificationTokens(p.userId());

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
            events.publishEvent(AuditEvent.of(p.userId(), AuditAction.ACCOUNT_ACTIVATED, AuditResource.USER, p.userId().toString(), emailDetails));
            return new ActivationStatusView(AccountStatus.ACTIVE.name());
        }

        String token = newToken();
        repository.insertVerificationToken(p.userId(), email, sha256(token), verificationHours);
        events.publishEvent(AuditEvent.of(p.userId(), AuditAction.ACTIVATION_STARTED, AuditResource.USER, p.userId().toString(), emailDetails));

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
        VerificationLink row = repository.lockLiveVerification(sha256(token))
            .orElseThrow(() -> ApiException.badRequest("VERIFICATION_LINK_INVALID", INVALID_LINK));

        UUID userId = row.userId();
        boolean consistent = row.tokenEmail().equals(row.userEmail())
            && AccountStatus.PENDING_ACTIVATION.name().equals(row.status())
            && row.hasPassword();
        if (!consistent) {
            throw ApiException.badRequest("VERIFICATION_LINK_INVALID", INVALID_LINK);
        }
        repository.consumeVerificationToken(row.tokenId());
        repository.markEmailVerifiedAndActive(userId);
        tempCredentials.invalidateActive(userId, "ACTIVATED");
        events.publishEvent(AuditEvent.of(userId, AuditAction.ACCOUNT_ACTIVATED, AuditResource.USER, userId.toString(),
            Map.of("emailVerified", true, "email", row.userEmail())));
        return new ActivationStatusView(AccountStatus.ACTIVE.name());
    }

    // ---- internals ----

    /**
     * Verifies identifier + temporary credential. Every failure mode (unknown account, wrong secret,
     * expired, locked) yields the same 401. Wrong secrets are counted per credential (lockout) and
     * per identifier/IP (throttle).
     */
    private PendingActivation authenticate(String identifier, String secret, String clientIp) {
        String id = identifier.trim().toLowerCase(Locale.ROOT);
        String throttleKey = "act:" + id;
        throttle.assertAllowed(throttleKey, clientIp);

        Optional<PendingActivation> found = repository.findPending(id);
        boolean matches = encoder.matches(secret, found.map(PendingActivation::secretHash).orElse(dummyHash));

        if (found.isEmpty() || !matches) {
            found.ifPresent(p -> recordCredentialFailure(p));
            throttle.record(throttleKey, clientIp, false);
            events.publishEvent(AuditEvent.of(found.map(PendingActivation::userId).orElse(null), AuditAction.ACTIVATION_FAILED, AuditResource.AUTH, null, Map.of()));
            throw ApiException.unauthorized("ACTIVATION_CREDENTIALS_INVALID", INVALID);
        }
        PendingActivation p = found.get();
        if (p.expiresAt().isBefore(OffsetDateTime.now())) {
            tempCredentials.invalidateActive(p.userId(), "EXPIRED");
            throttle.record(throttleKey, clientIp, false);
            throw ApiException.unauthorized("ACTIVATION_CREDENTIALS_INVALID", INVALID);
        }
        return p;
    }

    private void recordCredentialFailure(PendingActivation p) {
        int attempts = credentials.incrementFailedAttempts(p.credentialId());
        if (attempts >= maxCredentialAttempts) {
            tempCredentials.invalidateActive(p.userId(), "LOCKED");
            events.publishEvent(AuditEvent.of(p.userId(), AuditAction.TEMP_CREDENTIAL_LOCKED, AuditResource.USER, p.userId().toString(), Map.of()));
        }
    }

    private static String newToken() {
        return Tokens.newToken();
    }

    private static String sha256(String value) {
        return Tokens.sha256(value);
    }
}
