package com.blissfuljuan.dieapi.config;

import com.blissfuljuan.dieapi.config.ProductionSafetyCheck;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionSafetyCheckTest {

    private static MockEnvironment safeProduction() {
        return new MockEnvironment()
            .withProperty("server.servlet.session.cookie.secure", "true")
            .withProperty("die.mail.mode", "smtp")
            .withProperty("spring.datasource.username", "die_app")
            .withProperty("spring.datasource.password", "s3cret")
            .withProperty("spring.flyway.user", "die_migrator")
            .withProperty("die.messaging.username", "die")
            .withProperty("die.messaging.password", "a-long-random-broker-secret")
            .withProperty("die.frontend-base-url", "https://die.example.edu");
    }

    @Test
    void safeProductionSettingsHaveNoViolations() {
        assertThat(ProductionSafetyCheck.violations(safeProduction())).isEmpty();
    }

    @Test
    void localAndTestProfilesAreExemptEvenWithDevelopmentDefaults() {
        for (String profile : new String[] {"local", "test"}) {
            MockEnvironment env = new MockEnvironment()
                .withProperty("server.servlet.session.cookie.secure", "false")
                .withProperty("die.mail.mode", "log")
                .withProperty("spring.datasource.username", "postgres");
            env.setActiveProfiles(profile);
            assertThat(ProductionSafetyCheck.violations(env)).isEmpty();
        }
    }

    @Test
    void developmentDefaultsWithoutAnExplicitLocalProfileAreRejected() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("server.servlet.session.cookie.secure", "false")
            .withProperty("die.mail.mode", "log")
            .withProperty("spring.datasource.username", "postgres")
            .withProperty("spring.flyway.user", "postgres");
        // 5 database/cookie/mail problems + RabbitMQ user and password (messaging is on by default)
        assertThat(ProductionSafetyCheck.violations(env)).hasSize(7);
    }

    @Test
    void eachUnsafeSettingIsReportedOnItsOwn() {
        assertThat(ProductionSafetyCheck.violations(safeProduction().withProperty("die.mail.mode", "log")))
            .singleElement().asString().contains("die.mail.mode");
        assertThat(ProductionSafetyCheck.violations(
            safeProduction().withProperty("server.servlet.session.cookie.secure", "false")))
            .singleElement().asString().contains("Secure");
        assertThat(ProductionSafetyCheck.violations(safeProduction().withProperty("spring.datasource.username", "postgres")))
            .singleElement().asString().contains("least-privileged");
        assertThat(ProductionSafetyCheck.violations(safeProduction().withProperty("spring.flyway.user", "die_app")))
            .singleElement().asString().contains("differ");
        assertThat(ProductionSafetyCheck.violations(safeProduction().withProperty("spring.datasource.password", "")))
            .singleElement().asString().contains("password");
    }

    @Test
    void emptyOrDefaultBrokerCredentialsAreRejected() {
        for (String user : new String[] {"", "guest", "GUEST"}) {
            assertThat(ProductionSafetyCheck.violations(safeProduction().withProperty("die.messaging.username", user)))
                .singleElement().asString().contains("RABBITMQ_USER");
        }
        for (String password : new String[] {"", "guest", "change-me-rabbit", "Change-Me-Please-Now", "rabbitmq", "short-secret"}) {
            assertThat(ProductionSafetyCheck.violations(safeProduction().withProperty("die.messaging.password", password)))
                .as("password '%s'", password)
                .singleElement().asString().contains("RABBITMQ_PASSWORD");
        }
    }

    @Test
    void brokerCredentialsAreNotCheckedWhenMessagingIsDisabled() {
        MockEnvironment env = safeProduction().withProperty("die.messaging.enabled", "false")
            .withProperty("die.messaging.username", "guest").withProperty("die.messaging.password", "guest");
        assertThat(ProductionSafetyCheck.violations(env)).isEmpty();
    }

    @Test
    void theFrontendAddressMustBeHttps() {
        assertThat(ProductionSafetyCheck.violations(
            safeProduction().withProperty("die.frontend-base-url", "http://localhost:5173")))
            .singleElement().asString().contains("die.frontend-base-url");
        assertThat(ProductionSafetyCheck.violations(
            safeProduction().withProperty("die.frontend-base-url", "http://die.example.edu")))
            .singleElement().asString().contains("https");
    }

    @Test
    void mailDefaultsToSmtpWhenUnset() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("server.servlet.session.cookie.secure", "true")
            .withProperty("spring.datasource.username", "die_app")
            .withProperty("spring.datasource.password", "x")
            .withProperty("die.messaging.username", "die")
            .withProperty("die.messaging.password", "a-long-random-broker-secret")
            .withProperty("spring.flyway.user", "die_migrator");
        assertThat(ProductionSafetyCheck.violations(env)).isEmpty();
    }
}
