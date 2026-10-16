package com.blissfuljuan.dieapi.account.service;

import com.blissfuljuan.dieapi.account.repository.AccountRepository;
import com.blissfuljuan.dieapi.audit.model.AuditAction;
import com.blissfuljuan.dieapi.audit.model.AuditEvent;
import com.blissfuljuan.dieapi.audit.model.AuditResource;
import org.springframework.context.ApplicationEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Creates the first administrator when none exists and DIE_BOOTSTRAP_ADMIN_EMAIL /
 * DIE_BOOTSTRAP_ADMIN_PASSWORD are set. Does nothing once any admin exists, so the variables can
 * (and should) be removed after first start.
 */
@Component
public class BootstrapAdmin implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);
    private static final int MIN_PASSWORD_LENGTH = 12;

    private final AccountRepository accounts;
    private final PasswordEncoder encoder;
    private final ApplicationEventPublisher events;
    private final String email;
    private final String password;

    public BootstrapAdmin(AccountRepository accounts, PasswordEncoder encoder, ApplicationEventPublisher events,
                          @Value("${die.bootstrap.admin-email:}") String email,
                          @Value("${die.bootstrap.admin-password:}") String password) {
        this.accounts = accounts;
        this.encoder = encoder;
        this.events = events;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email.isBlank() || password.isBlank()) {
            return;
        }
        if (accounts.countAdmins() > 0) {
            return;
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                "DIE_BOOTSTRAP_ADMIN_PASSWORD must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        UUID id = accounts.insertBootstrapAdmin(email.trim().toLowerCase(Locale.ROOT), encoder.encode(password));
        events.publishEvent(AuditEvent.of(null, AuditAction.BOOTSTRAP_ADMIN_CREATED, AuditResource.USER, id.toString(), Map.of()));
        log.warn("Bootstrap administrator created. Remove DIE_BOOTSTRAP_ADMIN_* from the environment now.");
    }
}
