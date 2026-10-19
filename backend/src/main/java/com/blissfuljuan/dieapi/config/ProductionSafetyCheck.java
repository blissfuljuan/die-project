package com.blissfuljuan.dieapi.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Refuses to start with development-only settings unless the "local" (or "test") profile is
 * explicitly active. Prevents a forgotten SPRING_PROFILES_ACTIVE from shipping insecure cookies,
 * logged account-takeover links, or a superuser database connection.
 */
@Component
public class ProductionSafetyCheck {

    private static final Logger log = LoggerFactory.getLogger(ProductionSafetyCheck.class);

    private static final Set<String> WEAK_BROKER_PASSWORDS = Set.of("guest", "rabbitmq", "password", "die", "admin", "secret");
    private static final int MIN_BROKER_PASSWORD_LENGTH = 16;

    private final Environment env;

    public ProductionSafetyCheck(Environment env) {
        this.env = env;
    }

    @PostConstruct
    void verify() {
        List<String> problems = violations(env);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start with unsafe configuration:\n - "
                + String.join("\n - ", problems)
                + "\nFix the settings, or run with SPRING_PROFILES_ACTIVE=local for development.");
        }
        if (Set.of(env.getActiveProfiles()).contains("local")) {
            log.warn("Running with the 'local' profile: development defaults are active. Never use it in production.");
        }
    }

    static List<String> violations(Environment env) {
        Set<String> profiles = Set.of(env.getActiveProfiles());
        if (profiles.contains("local") || profiles.contains("test")) {
            return List.of();
        }
        List<String> problems = new ArrayList<>();
        if (!env.getProperty("server.servlet.session.cookie.secure", Boolean.class, true)) {
            problems.add("session cookies are not Secure (server.servlet.session.cookie.secure=false)");
        }
        if ("log".equalsIgnoreCase(env.getProperty("die.mail.mode", "smtp"))) {
            problems.add("die.mail.mode=log writes activation/reset links to the logs; use smtp");
        }
        String appUser = env.getProperty("spring.datasource.username", "");
        if (appUser.isBlank() || "postgres".equalsIgnoreCase(appUser)) {
            problems.add("runtime database user must be a least-privileged role (e.g. die_app), not '" + appUser + "'");
        }
        if (appUser.equals(env.getProperty("spring.flyway.user", ""))) {
            problems.add("runtime database user must differ from the Flyway migration user");
        }
        if (env.getProperty("spring.datasource.password", "").isBlank()) {
            problems.add("spring.datasource.password is empty");
        }
        if (env.getProperty("die.messaging.enabled", Boolean.class, true)) {
            problems.addAll(brokerCredentialProblems(
                env.getProperty("die.messaging.username", ""), env.getProperty("die.messaging.password", "")));
        }
        String frontend = env.getProperty("die.frontend-base-url", "");
        if (!frontend.isBlank() && !frontend.toLowerCase(Locale.ROOT).startsWith("https://")) {
            problems.add("die.frontend-base-url must be the public https:// address of the frontend (activation and reset links use it), not '"
                + frontend + "'");
        }
        return problems;
    }

    /** Known-bad broker logins: empty, the broker's built-in guest account, example values from .env.example, too short. */
    private static List<String> brokerCredentialProblems(String user, String password) {
        List<String> problems = new ArrayList<>();
        if (user.isBlank() || user.equalsIgnoreCase("guest")) {
            problems.add("RabbitMQ user (RABBITMQ_USER) is empty or the default 'guest' account");
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (password.isBlank() || WEAK_BROKER_PASSWORDS.contains(lower) || lower.startsWith("change-me")) {
            problems.add("RabbitMQ password (RABBITMQ_PASSWORD) is empty or a default/example value");
        } else if (password.length() < MIN_BROKER_PASSWORD_LENGTH) {
            problems.add("RabbitMQ password (RABBITMQ_PASSWORD) is shorter than " + MIN_BROKER_PASSWORD_LENGTH + " characters");
        }
        return problems;
    }
}
