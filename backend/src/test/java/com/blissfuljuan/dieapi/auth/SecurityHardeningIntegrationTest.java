package com.blissfuljuan.dieapi.auth;

import com.blissfuljuan.dieapi.PostgresIntegrationTest;
import com.blissfuljuan.dieapi.support.CapturingEmailSender;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Regression tests for the findings of the P1 security review. */
@Import(CapturingEmailSender.Config.class)
class SecurityHardeningIntegrationTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct horse battery staple";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired AuthenticationManager authenticationManager;
    @Autowired CapturingEmailSender mail;

    UUID userId;

    @BeforeEach
    void seed() {
        mail.sent.clear();
        jdbc.update("DELETE FROM login_attempt");
        jdbc.update("DELETE FROM password_reset_token");
        jdbc.update("DELETE FROM app_user");
        userId = insertUser("teacher@example.edu", "INSTRUCTOR", "ACTIVE");
    }

    private UUID insertUser(String email, String role, String status) {
        return jdbc.queryForObject("""
            INSERT INTO app_user (role, status, last_name, first_name, email, email_verified_at, password_hash)
            VALUES (?, ?, 'Doe', 'Jane', ?, now(), ?) RETURNING id
            """, UUID.class, role, status, email, encoder.encode(PASSWORD));
    }

    private MvcResult postJson(String url, String remoteAddr, String json) throws Exception {
        MockHttpServletRequestBuilder req = post(url).with(csrf())
            .with(r -> { r.setRemoteAddr(remoteAddr); return r; })
            .contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(req).andReturn();
    }

    private MvcResult login(String email, String password, String ip) throws Exception {
        return postJson("/api/v1/auth/login", ip, "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    private Cookie signIn() throws Exception {
        MvcResult r = login("teacher@example.edu", PASSWORD, "10.1.1.1");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return r.getResponse().getHeaders("Set-Cookie").stream()
            .filter(h -> h.startsWith("SESSION="))
            .map(h -> new Cookie("SESSION", h.substring("SESSION=".length(), h.indexOf(';'))))
            .findFirst().orElseThrow();
    }

    private int me(Cookie cookie) throws Exception {
        return mvc.perform(get("/api/v1/auth/me").cookie(cookie)).andReturn().getResponse().getStatus();
    }

    // ---- principal / session store ----

    @Test
    void passwordHashIsNeverPersistedInTheSessionStore() throws Exception {
        signIn();
        List<byte[]> blobs = jdbc.query("SELECT attribute_bytes FROM spring_session_attributes",
            (rs, n) -> rs.getBytes(1));
        assertThat(blobs).isNotEmpty();
        for (byte[] blob : blobs) {
            assertThat(new String(blob, StandardCharsets.ISO_8859_1)).doesNotContain("$argon2");
        }
    }

    // ---- per-request revalidation (defence in depth) ----

    @Test
    void sessionIsDroppedWhenAccountIsDeactivatedOutsideTheApi() throws Exception {
        Cookie session = signIn();
        assertThat(me(session)).isEqualTo(200);
        jdbc.update("UPDATE app_user SET status = 'DEACTIVATED' WHERE id = ?", userId);
        assertThat(me(session)).isEqualTo(401);
        // The server-side session itself is gone, not merely ignored.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name = ?",
            Integer.class, userId.toString())).isZero();
    }

    @Test
    void sessionIsDroppedWhenRoleChangesOutsideTheApi() throws Exception {
        Cookie session = signIn();
        jdbc.update("UPDATE app_user SET role = 'ADMIN' WHERE id = ?", userId);
        assertThat(me(session)).isEqualTo(401);
    }

    @Test
    void sessionIsDroppedWhenAccountIsDeleted() throws Exception {
        Cookie session = signIn();
        jdbc.update("DELETE FROM app_user WHERE id = ?", userId);
        assertThat(me(session)).isEqualTo(401);
    }

    // ---- timing: deactivated accounts must pay for the password check ----

    @Test
    void deactivatedAccountIsOnlyReportedAfterThePasswordIsVerified() {
        insertUser("gone@example.edu", "INSTRUCTOR", "DEACTIVATED");
        assertThatThrownBy(() -> authenticationManager.authenticate(
            UsernamePasswordAuthenticationToken.unauthenticated("gone@example.edu", "wrong password here")))
            .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> authenticationManager.authenticate(
            UsernamePasswordAuthenticationToken.unauthenticated("gone@example.edu", PASSWORD)))
            .isInstanceOf(DisabledException.class);
    }

    // ---- throttling buckets ----

    @Test
    void ipThrottleIsPerClientAddress() throws Exception {
        for (int i = 0; i < 20; i++) {
            assertThat(login("nobody" + i + "@example.edu", "x", "10.9.9.9").getResponse().getStatus()).isEqualTo(401);
        }
        assertThat(login("another@example.edu", "x", "10.9.9.9").getResponse().getStatus()).isEqualTo(429);
        assertThat(login("another@example.edu", "x", "10.8.8.8").getResponse().getStatus()).isEqualTo(401);
        assertThat(login("teacher@example.edu", PASSWORD, "10.8.8.8").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void passwordResetRequestsNeverConsumeTheLoginBudgetOfTheSameAddress() throws Exception {
        for (int i = 0; i < 20; i++) {
            MvcResult r = postJson("/api/v1/auth/password-reset/request", "10.7.7.7",
                "{\"email\":\"victim" + i + "@example.edu\"}");
            assertThat(r.getResponse().getStatus()).isEqualTo(202);
        }
        // The reset budget of that address is spent...
        assertThat(postJson("/api/v1/auth/password-reset/request", "10.7.7.7",
            "{\"email\":\"one-more@example.edu\"}").getResponse().getStatus()).isEqualTo(429);
        // ...but people behind the same address can still sign in.
        assertThat(login("teacher@example.edu", PASSWORD, "10.7.7.7").getResponse().getStatus()).isEqualTo(200);
    }

    // ---- V4: least-privilege role ----

    @Test
    void appRoleGrantsGiveDataAccessButNeverAuditMutationOrMigrationHistory() throws Exception {
        jdbc.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'die_app') "
            + "THEN CREATE ROLE die_app LOGIN; END IF; END $$");
        String script = new String(new ClassPathResource("db/migration/V4__app_role_grants.sql")
            .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        jdbc.execute(script);

        assertThat(privilege("app_user", "INSERT")).isTrue();
        assertThat(privilege("app_user", "UPDATE")).isTrue();
        assertThat(privilege("audit_log", "INSERT")).isTrue();
        assertThat(privilege("audit_log", "UPDATE")).isFalse();
        assertThat(privilege("audit_log", "DELETE")).isFalse();
        assertThat(privilege("flyway_schema_history", "INSERT")).isFalse();
        assertThat(privilege("flyway_schema_history", "SELECT")).isFalse();
    }

    private boolean privilege(String table, String privilege) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT has_table_privilege('die_app', ?, ?)", Boolean.class, "public." + table, privilege));
    }
}
