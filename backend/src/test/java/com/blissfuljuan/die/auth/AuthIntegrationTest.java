package com.blissfuljuan.die.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.blissfuljuan.die.PostgresIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

class AuthIntegrationTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct horse battery staple";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM login_attempt");
        jdbc.update("DELETE FROM app_user");
        insertUser("instructor@example.edu", "INSTRUCTOR", "ACTIVE");
        insertUser("gone@example.edu", "INSTRUCTOR", "DEACTIVATED");
    }

    private void insertUser(String email, String role, String status) {
        jdbc.update("""
            INSERT INTO app_user (role, status, last_name, first_name, email, email_verified_at, password_hash)
            VALUES (?, ?, 'Doe', 'Jane', ?, now(), ?)
            """, role, status, email, encoder.encode(PASSWORD));
    }

    /** Error bodies carry a per-request timestamp and traceId; "indistinguishable" means the rest is identical. */
    private static String stable(String json) { return json.replaceAll(",?\"(timestamp|traceId)\":\"[^\"]*\"", ""); }

    private MvcResult login(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
            .andReturn();
    }

    /** Spring Session writes the cookie as a raw Set-Cookie header. */
    private static Cookie sessionCookie(MvcResult result) {
        return result.getResponse().getHeaders("Set-Cookie").stream()
            .filter(h -> h.startsWith("SESSION="))
            .map(h -> new Cookie("SESSION", h.substring("SESSION=".length(), h.indexOf(';'))))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no SESSION cookie issued"));
    }

    @Test
    void loginSucceedsAndSessionAuthenticatesMe() throws Exception {
        MvcResult result = login("Instructor@Example.edu", PASSWORD);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("\"role\":\"INSTRUCTOR\"");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("password");

        mvc.perform(get("/api/v1/auth/me").cookie(sessionCookie(result)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("instructor@example.edu"));
    }

    @Test
    void wrongPasswordUnknownUserAndDeactivatedUserAreIndistinguishable() throws Exception {
        String wrong = stable(login("instructor@example.edu", "nope").getResponse().getContentAsString());
        String unknown = stable(login("nobody@example.edu", "nope").getResponse().getContentAsString());
        MvcResult deactivated = login("gone@example.edu", PASSWORD);

        assertThat(deactivated.getResponse().getStatus()).isEqualTo(401);
        assertThat(wrong).isEqualTo(unknown);
        assertThat(stable(deactivated.getResponse().getContentAsString())).isEqualTo(wrong);
    }

    @Test
    void loginRequiresCsrfToken() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"instructor@example.edu\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void meRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void lockoutAfterRepeatedFailuresBlocksEvenCorrectPassword() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(login("instructor@example.edu", "bad").getResponse().getStatus()).isEqualTo(401);
        }
        assertThat(login("instructor@example.edu", PASSWORD).getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    void logoutInvalidatesSession() throws Exception {
        MvcResult result = login("instructor@example.edu", PASSWORD);
        Cookie session = sessionCookie(result);

        mvc.perform(post("/api/v1/auth/logout").with(csrf()).cookie(session))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").cookie(session))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void loginWritesAuditRows() throws Exception {
        login("instructor@example.edu", PASSWORD);
        login("instructor@example.edu", "bad");
        Integer rows = jdbc.queryForObject(
            "SELECT count(*) FROM audit_log WHERE action IN ('LOGIN_SUCCEEDED','LOGIN_FAILED')", Integer.class);
        assertThat(rows).isGreaterThanOrEqualTo(2);
    }
}
