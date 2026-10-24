package com.blissfuljuan.dieapi.auth.service.adapter;

import com.blissfuljuan.dieapi.auth.service.LoginAttemptPurger;
import com.blissfuljuan.dieapi.common.util.PeriodicTask;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Runs {@link LoginAttemptPurger#purgeOnce()} periodically. Disable with die.auth.purge.enabled=false (tests call it by hand). */
@Component
@ConditionalOnProperty(name = "die.auth.purge.enabled", havingValue = "true", matchIfMissing = true)
public class LoginAttemptPurgeRunner extends PeriodicTask {

    public LoginAttemptPurgeRunner(LoginAttemptPurger purger, @Value("${die.auth.purge.interval-minutes:60}") long intervalMinutes) {
        super("login-attempt-purge", 60, intervalMinutes * 60, purger::purgeOnce);
    }
}
