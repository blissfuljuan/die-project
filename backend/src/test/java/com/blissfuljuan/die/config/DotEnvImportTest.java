package com.blissfuljuan.die.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * Documents how backend/.env (imported through spring.config.import=optional:file:.env[.properties]) behaves,
 * because two of its quirks cost a startup failure once:
 *  - profiles are activated by the dotted key (spring.profiles.active=local); SPRING_PROFILES_ACTIVE written
 *    in the file is just an unrelated property and is ignored;
 *  - an empty line such as {@code DIE_MAIL_MODE=} defines the property as the empty string, so a
 *    {@code ${DIE_MAIL_MODE:smtp}} default is NOT used (MailConfig treats blank as smtp).
 */
class DotEnvImportTest {

    @Configuration
    static class Empty {}

    private static ConfigurableApplicationContext start(Path dir, String content) throws IOException {
        Path env = dir.resolve(".env");
        Files.writeString(env, content, StandardCharsets.UTF_8);
        return new SpringApplicationBuilder(Empty.class).web(WebApplicationType.NONE).bannerMode(org.springframework.boot.Banner.Mode.OFF)
            .run("--spring.config.name=dotenv-test-only", "--spring.config.import=optional:file:" + env + "[.properties]");
    }

    @Test
    void theDottedProfileKeyActivatesTheLocalProfile(@TempDir Path dir) throws IOException {
        try (ConfigurableApplicationContext ctx = start(dir, "DB_URL=jdbc:postgresql://x/y\nspring.profiles.active=local\n")) {
            assertThat(ctx.getEnvironment().getActiveProfiles()).containsExactly("local");
            assertThat(ctx.getEnvironment().getProperty("DB_URL")).isEqualTo("jdbc:postgresql://x/y");
        }
    }

    @Test
    void theEnvironmentStyleKeyInsideTheFileDoesNotActivateAnyProfile(@TempDir Path dir) throws IOException {
        try (ConfigurableApplicationContext ctx = start(dir, "SPRING_PROFILES_ACTIVE=local\n")) {
            assertThat(ctx.getEnvironment().getActiveProfiles()).isEmpty();
        }
    }

    @Test
    void anEmptyValueShadowsThePlaceholderDefault(@TempDir Path dir) throws IOException {
        try (ConfigurableApplicationContext ctx = start(dir, "DIE_MAIL_MODE=\n")) {
            assertThat(ctx.getEnvironment().resolvePlaceholders("${DIE_MAIL_MODE:smtp}")).isEmpty();
        }
    }

    @Test
    void spacesAroundTheEqualsSignStillDefineTheKey(@TempDir Path dir) throws IOException {
        try (ConfigurableApplicationContext ctx = start(dir, "DIE_BOOTSTRAP_ADMIN_EMAIL =admin@school.edu\n")) {
            assertThat(ctx.getEnvironment().resolvePlaceholders("${DIE_BOOTSTRAP_ADMIN_EMAIL:}")).isEqualTo("admin@school.edu");
        }
    }
}
