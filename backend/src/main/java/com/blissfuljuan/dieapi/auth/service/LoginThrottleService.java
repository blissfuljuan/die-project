package com.blissfuljuan.dieapi.auth.service;

import com.blissfuljuan.dieapi.auth.model.rule.ThrottleBucket;
import com.blissfuljuan.dieapi.auth.model.rule.ThrottlePolicy;
import com.blissfuljuan.dieapi.auth.repository.LoginAttemptRepository;
import com.blissfuljuan.dieapi.common.exception.ApiException;
import com.blissfuljuan.dieapi.common.exception.ErrorCode;
import com.blissfuljuan.dieapi.audit.model.AuditAction;
import com.blissfuljuan.dieapi.audit.model.AuditEvent;
import com.blissfuljuan.dieapi.audit.model.AuditResource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Failed-attempt throttling per identifier and per client address, backed by login_attempt so it
 * holds across API instances. Used by login, activation and password change/reset. Identifiers are
 * namespaced ("act:", "pwchange:", "pwreset:" prefixes; login uses the bare email).
 *
 * Password-reset requests are tracked in their own {@link ThrottleBucket}: they must never consume the
 * per-IP login budget, otherwise anonymous reset requests could lock everyone behind one address out of
 * signing in. The limits are one {@link ThrottlePolicy}; this class counts (via the repository) and enforces.
 */
@Service
public class LoginThrottleService {

    private final LoginAttemptRepository attempts;
    private final ThrottlePolicy policy;
    private final ApplicationEventPublisher events;

    public LoginThrottleService(LoginAttemptRepository attempts, ApplicationEventPublisher events,
                                @Value("${die.auth.max-failed-per-identifier:5}") int maxPerIdentifier,
                                @Value("${die.auth.max-failed-per-ip:20}") int maxPerIp,
                                @Value("${die.auth.throttle-window-minutes:15}") int windowMinutes,
                                @Value("${die.auth.max-failed-per-identifier-any-address:30}") int maxPerIdentifierAnyAddress) {
        this.attempts = attempts;
        this.events = events;
        this.policy = new ThrottlePolicy(maxPerIdentifier, maxPerIp, windowMinutes, maxPerIdentifierAnyAddress);
    }

    /**
     * For login/activation/password-change: throws 429 when the identifier or the address (excluding
     * reset requests) has too many failures. Failures are counted since the identifier's last success.
     */
    public void assertAllowed(String identifier, String clientIp) {
        assertAllowed(ThrottleBucket.LOGIN, identifier, clientIp);
    }

    /** For password-reset requests: own per-identifier and per-IP budgets. Same answer for any address. */
    public void assertResetAllowed(String resetKey, String clientIp) {
        assertAllowed(ThrottleBucket.PASSWORD_RESET, resetKey, clientIp);
    }

    /** Stores the attempt. A failure that uses up a budget is audited once (THROTTLE_TRIGGERED), without the identifier. */
    public void record(String identifier, String clientIp, boolean succeeded) {
        attempts.insert(identifier, clientIp, succeeded);
        if (succeeded) {
            return;
        }
        ThrottleBucket bucket = identifier.startsWith(ThrottleBucket.RESET_PREFIX) ? ThrottleBucket.PASSWORD_RESET : ThrottleBucket.LOGIN;
        Counts c = count(bucket, identifier, clientIp);
        String limit = policy.limitJustReached(c.fromAddress(), c.anyAddress(), c.address());
        if (limit != null) {
            events.publishEvent(AuditEvent.of(null, AuditAction.THROTTLE_TRIGGERED, AuditResource.AUTH, null,
                Map.of("bucket", bucket.name(), "limit", limit, "ip", String.valueOf(clientIp))));
        }
    }

    private void assertAllowed(ThrottleBucket bucket, String identifier, String clientIp) {
        Counts c = count(bucket, identifier, clientIp);
        if (policy.blocks(c.fromAddress(), c.anyAddress(), c.address())) {
            throw ApiException.tooManyRequests(ErrorCode.TOO_MANY_ATTEMPTS, "Too many attempts. Wait a while before trying again.");
        }
    }

    private record Counts(long fromAddress, long anyAddress, long address) {}

    private Counts count(ThrottleBucket bucket, String identifier, String clientIp) {
        int window = policy.windowMinutes();
        long anyAddress = attempts.failuresForIdentifier(identifier, window);
        long fromAddress = clientIp == null ? anyAddress : attempts.failuresForIdentifierFromIp(identifier, clientIp, window);
        long address = clientIp == null ? 0 : switch (bucket) {
            case LOGIN -> attempts.failuresForIpExcludingPrefix(clientIp, ThrottleBucket.RESET_PREFIX, window);
            case PASSWORD_RESET -> attempts.failuresForIpWithPrefix(clientIp, ThrottleBucket.RESET_PREFIX, window);
        };
        return new Counts(fromAddress, anyAddress, address);
    }
}
