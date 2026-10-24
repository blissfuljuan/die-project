package com.blissfuljuan.dieapi.common;

import com.blissfuljuan.dieapi.PostgresIntegrationTest;
import com.blissfuljuan.dieapi.support.LogCapture;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Secrets must never reach the logs: passwords (right and wrong), session cookies, Authorization headers, CSRF tokens,
 * session ids and CSRF tokens. (Email link tokens are deliberately printed by die.mail.mode=log, a development-only mode that
 * ProductionSafetyCheck refuses outside local/test, so they are not asserted here.) Runs with DEBUG enabled for the application,
 * Spring Security and Spring Web, which is the noisiest realistic setting. The messaging half of the requirement
 * (broker password, outbox flow) is asserted in MessagingIntegrationTest.
 */
class LogSanitizationTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "Sup3r-Secret-Login-Pw!";
    private static final String WRONG_PASSWORD = "Wr0ng-Secret-Guess-Pw!";
    private static final String BEARER = "bearer-token-should-never-be-logged-123";
    private static final String BASIC = "Basic dXNlcjpzZWNyZXQtYmFzaWMtY3JlZA==";
    private static final String FOREIGN_COOKIE = "foreign-cookie-value-should-not-appear";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;

    @Test
    void loginResetAndHeaderValuesNeverAppearInTheLogs() throws Exception {
        jdbc.update("""
            INSERT INTO app_user (role, status, last_name, first_name, email, email_verified_at, password_hash)
            VALUES ('ADMIN', 'ACTIVE', 'Log', 'Test', 'log-admin@x.edu', now(), ?)
            """, encoder.encode(PASSWORD));

        try (LogCapture logs = new LogCapture("com.blissfuljuan", "org.springframework.security", "org.springframework.web")) {
            // wrong password, with hostile-looking headers
            mvc.perform(post("/api/v1/auth/login").with(csrf())
                .header("Authorization", "Bearer " + BEARER)
                .header("X-Request-Id", "sanitize-corr-0001")
                .cookie(new Cookie("tracking", FOREIGN_COOKIE))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"log-admin@x.edu\",\"password\":\"" + WRONG_PASSWORD + "\"}")).andReturn();

            // successful login, then an authenticated call with the session cookie
            MvcResult ok = mvc.perform(post("/api/v1/auth/login").with(csrf())
                .header("Authorization", BASIC)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"log-admin@x.edu\",\"password\":\"" + PASSWORD + "\"}")).andReturn();
            assertThat(ok.getResponse().getStatus()).isEqualTo(200);
            String sessionId = ok.getResponse().getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith("SESSION=")).map(h -> h.substring(8, h.indexOf(';'))).findFirst().orElseThrow();
            mvc.perform(get("/api/v1/auth/me").cookie(new Cookie("SESSION", sessionId))
                .header("Authorization", "Bearer " + BEARER)).andReturn();

            // CSRF failure carries the (wrong) token header
            mvc.perform(post("/api/v1/auth/logout").header("X-XSRF-TOKEN", "csrf-token-value-should-not-appear")
                .cookie(new Cookie("SESSION", sessionId))).andReturn();

            // password reset request for a known address
            mvc.perform(post("/api/v1/auth/password-reset/request").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"log-admin@x.edu\"}")).andReturn();

            // an unexpected failure path: malformed JSON containing a password-like value
            mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"log-admin@x.edu\",\"password\":\"" + WRONG_PASSWORD + "\"")).andReturn();

            String text = logs.text();
            assertThat(logs.lines()).as("something was captured, otherwise this test proves nothing").isNotEmpty();
            assertThat(text).as("the correlation id of the request is in the logs").contains("sanitize-corr-0001");
            assertThat(text)
                .doesNotContain(PASSWORD)
                .doesNotContain(WRONG_PASSWORD)
                .doesNotContain(BEARER)
                .doesNotContain("dXNlcjpzZWNyZXQtYmFzaWMtY3JlZA")
                .doesNotContain(FOREIGN_COOKIE)
                .doesNotContain(sessionId)
                .doesNotContain("csrf-token-value-should-not-appear");
        }
    }
}
