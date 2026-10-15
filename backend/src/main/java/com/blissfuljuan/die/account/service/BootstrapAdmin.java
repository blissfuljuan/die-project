package com.blissfuljuan.die.account.service;

import com.blissfuljuan.die.audit.service.AuditService;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the first administrator when none exists and DIE_BOOTSTRAP_ADMIN_EMAIL /
 * DIE_BOOTSTRAP_ADMIN_PASSWORD are set. Does nothing once any admin exists, so the variables can
 * (and should) be removed after first start.
 */
@Component
public class BootstrapAdmin implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);
    private static final int MIN_PASSWORD_LENGTH = 12;

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final String email;
    private final String password;

    public BootstrapAdmin(JdbcClient jdbc, PasswordEncoder encoder, AuditService audit,
                          @Value("${die.bootstrap.admin-email:}") String email,
                          @Value("${die.bootstrap.admin-password:}") String password) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.audit = audit;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email.isBlank() || password.isBlank()) {
            return;
        }
        Long admins = jdbc.sql("SELECT count(*) FROM app_user WHERE role = 'ADMIN'").query(Long.class).single();
        if (admins > 0) {
            return;
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                "DIE_BOOTSTRAP_ADMIN_PASSWORD must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        UUID id = jdbc.sql("""
                INSERT INTO app_user (role, status, last_name, first_name, email, email_verified_at, password_hash)
                VALUES ('ADMIN', 'ACTIVE', 'Administrator', 'System', :email, now(), :hash)
                RETURNING id
                """)
            .param("email", email.trim().toLowerCase(Locale.ROOT))
            .param("hash", encoder.encode(password))
            .query(UUID.class).single();
        audit.record(null, "BOOTSTRAP_ADMIN_CREATED", "user", id.toString(), Map.of());
        log.warn("Bootstrap administrator created. Remove DIE_BOOTSTRAP_ADMIN_* from the environment now.");
    }
}
