package com.blissfuljuan.die;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base for integration tests: real Postgres via Testcontainers, Flyway migrations applied.
 * Skipped without Docker. One container is shared by every test class (singleton pattern) so the
 * cached Spring context never points at a stopped container; it is started lazily when the first
 * context is built and removed at JVM exit.
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
// Hermetic: the build runs tests from target/ (see pom.xml) so a developer's backend/.env is never imported;
// application-test.yml additionally neutralizes the settings that .env typically carries.
@SpringBootTest
@AutoConfigureMockMvc
public abstract class PostgresIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JdbcTemplate baseJdbc;

    /**
     * Every test starts from an empty database. TRUNCATE ... CASCADE follows foreign keys, so tables
     * added by later phases that reference app_user are cleared too and no test needs its own cleanup.
     */
    @BeforeEach
    void resetDatabase() {
        baseJdbc.execute("TRUNCATE app_user, audit_log, login_attempt, outbox_event, spring_session CASCADE");
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        synchronized (POSTGRES) {
            if (!POSTGRES.isRunning()) {
                POSTGRES.start();
            }
        }
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user", POSTGRES::getUsername);
        r.add("spring.flyway.password", POSTGRES::getPassword);
    }
}
