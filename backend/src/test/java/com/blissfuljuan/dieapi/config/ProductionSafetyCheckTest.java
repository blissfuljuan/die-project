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
            .withProperty("spring.flyway.user", "die_migrator");
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
        assertThat(ProductionSafetyCheck.violations(env)).hasSize(5);
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
    void mailDefaultsToSmtpWhenUnset() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("server.servlet.session.cookie.secure", "true")
            .withProperty("spring.datasource.username", "die_app")
            .withProperty("spring.datasource.password", "x")
            .withProperty("spring.flyway.user", "die_migrator");
        assertThat(ProductionSafetyCheck.violations(env)).isEmpty();
    }
}
