package com.blissfuljuan.dieapi.auth.service;

import com.blissfuljuan.dieapi.auth.repository.LoginAttemptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Removes old login_attempt rows (they hold identifiers and client addresses, and only the throttle window matters).
 * Retention must stay well above the throttle window; the default is seven days. Safe to run on several instances at
 * once: it is a single idempotent DELETE.
 */
@Service
public class LoginAttemptPurger {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptPurger.class);

    private final LoginAttemptRepository attempts;
    private final int retentionDays;

    public LoginAttemptPurger(LoginAttemptRepository attempts, @Value("${die.auth.attempt-retention-days:7}") int retentionDays) {
        if (retentionDays < 1) {
            throw new IllegalArgumentException("die.auth.attempt-retention-days must be at least 1");
        }
        this.attempts = attempts;
        this.retentionDays = retentionDays;
    }

    /** One pass; returns the number of rows removed. */
    public int purgeOnce() {
        int removed = attempts.purgeOlderThanDays(retentionDays);
        if (removed > 0) {
            log.info("purged {} login attempts older than {} days", removed, retentionDays);
        }
        return removed;
    }
}
