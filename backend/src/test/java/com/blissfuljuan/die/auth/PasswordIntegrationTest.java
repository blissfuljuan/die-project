package com.blissfuljuan.die.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.blissfuljuan.die.PostgresIntegrationTest;
import com.blissfuljuan.die.support.CapturingEmailSender;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@Import(CapturingEmailSender.Config.class)
class PasswordIntegrationTest extends PostgresIntegrationTest {

    private static final String OLD = "old passphrase 123";
    private static final String NEW = "brand new passphrase 456";
    private static final String EMAIL = "teacher@example.edu";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired CapturingEmailSender mail;

    UUID userId;

    @BeforeEach
    void seed() {
        mail.sent.clear();
        jdbc.update("DELETE FROM password_reset_token");
        jdbc.update("DELETE FROM temp_credential");
        jdbc.update("DELETE FROM login_attempt");
        jdbc.update("DELETE FROM app_user");
        userId = insertUser(EMAIL, "ACTIVE", true);
    }

    private UUID insertUser(String email, String status, boolean withPassword) {
        return jdbc.queryForObject("""
            INSERT INTO app_user (role, status, last_name, first_name, email, email_verified_at, password_hash)
            VALUES ('INSTRUCTOR', ?, 'Doe', 'Jane', ?, now(), ?) RETURNING id
            """, UUID.class, status, email, withPassword ? encoder.encode(OLD) : null);
    }

    // ---- helpers ----

    private MvcResult postJson(String url, Cookie cookie, String json) throws Exception {
        MockHttpServletRequestBuilder req = post(url).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json);
        if (cookie != null) req.cookie(cookie);
        return mvc.perform(req).andReturn();
    }

    private MvcResult login(String email, String password) throws Exception {
        return postJson("/api/v1/auth/login", null, "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    private Cookie signIn(String password) throws Exception {
        MvcResult r = login(EMAIL, password);
        assertThat(status(r)).as("sign in").isEqualTo(200);
        return r.getResponse().getHeaders("Set-Cookie").stream()
            .filter(h -> h.startsWith("SESSION="))
            .map(h -> new Cookie("SESSION", h.substring("SESSION=".length(), h.indexOf(';'))))
            .findFirst().orElseThrow();
    }

    private MvcResult requestReset(String email) throws Exception {
        return postJson("/api/v1/auth/password-reset/request", null, "{\"email\":\"" + email + "\"}");
    }

    private MvcResult confirmReset(String token, String password) throws Exception {
        return postJson("/api/v1/auth/password-reset/confirm", null,
            "{\"token\":\"" + token + "\",\"newPassword\":\"" + password + "\"}");
    }

    private MvcResult change(Cookie cookie, String current, String next) throws Exception {
        return postJson("/api/v1/auth/password/change", cookie,
            "{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}");
    }

    private int meStatus(Cookie cookie) throws Exception {
        return mvc.perform(get("/api/v1/auth/me").cookie(cookie)).andReturn().getResponse().getStatus();
    }

    private static int status(MvcResult r) { return r.getResponse().getStatus(); }
    private static String body(MvcResult r) throws Exception { return r.getResponse().getContentAsString(); }

    // ---- reset: request ----

    @Test
    void resetFlowChangesPasswordRevokesSessionsAndNotifiesTheUser() throws Exception {
        Cookie session = signIn(OLD);

        MvcResult requested = requestReset("Teacher@Example.edu");
        assertThat(status(requested)).isEqualTo(202);
        assertThat(mail.sent).hasSize(1);
        assertThat(mail.sent.get(0).to()).isEqualTo(EMAIL);
        String token = mail.sent.get(0).token();
        assertThat(jdbc.queryForObject("SELECT token_hash FROM password_reset_token WHERE user_id = ?", String.class, userId))
            .hasSize(64).isNotEqualTo(token);

        MvcResult confirmed = confirmReset(token, NEW);
        assertThat(status(confirmed)).isEqualTo(200);
        assertThat(confirmed.getResponse().getHeader("Cache-Control")).contains("no-store");

        assertThat(meStatus(session)).isEqualTo(401);            // old session revoked
        assertThat(status(login(EMAIL, OLD))).isEqualTo(401);
        assertThat(status(login(EMAIL, NEW))).isEqualTo(200);
        assertThat(status(confirmReset(token, "another passphrase 789"))).isEqualTo(400); // single use

        assertThat(mail.sent).hasSize(2);
        assertThat(mail.sent.get(1).subject()).isEqualTo("Your password was changed");
        assertThat(mail.sent.get(1).body()).doesNotContain(NEW).doesNotContain(OLD);

        Integer audits = jdbc.queryForObject(
            "SELECT count(*) FROM audit_log WHERE actor_user_id = ? AND action IN ('PASSWORD_RESET_REQUESTED','PASSWORD_RESET_COMPLETED')",
            Integer.class, userId);
        assertThat(audits).isEqualTo(2);
    }

    @Test
    void requestRespondsIdenticallyForKnownUnknownPendingAndDeactivatedAddresses() throws Exception {
        insertUser("pending@example.edu", "PENDING_ACTIVATION", false);
        insertUser("gone@example.edu", "DEACTIVATED", true);

        String known = body(requestReset(EMAIL));
        for (String email : new String[] {"nobody@example.edu", "pending@example.edu", "gone@example.edu"}) {
            MvcResult r = requestReset(email);
            assertThat(status(r)).isEqualTo(202);
            assertThat(body(r)).isEqualTo(known);
        }
        assertThat(mail.sent).hasSize(1);
        assertThat(mail.sent.get(0).to()).isEqualTo(EMAIL);
    }

    @Test
    void requestsAreThrottledIdenticallyForAnyAddress() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(status(requestReset(EMAIL))).isEqualTo(202);
            assertThat(status(requestReset("nobody@example.edu"))).isEqualTo(202);
        }
        assertThat(status(requestReset(EMAIL))).isEqualTo(429);
        assertThat(status(requestReset("nobody@example.edu"))).isEqualTo(429);
    }

    @Test
    void aNewRequestVoidsTheOlderLink() throws Exception {
        requestReset(EMAIL);
        requestReset(EMAIL);
        assertThat(mail.sent).hasSize(2);
        assertThat(status(confirmReset(mail.sent.get(0).token(), NEW))).isEqualTo(400);
        assertThat(status(confirmReset(mail.sent.get(1).token(), NEW))).isEqualTo(200);
    }

    // ---- reset: confirm ----

    @Test
    void weakPasswordIsRejectedAndTheLinkStaysUsable() throws Exception {
        requestReset(EMAIL);
        String token = mail.sent.get(0).token();
        assertThat(status(confirmReset(token, "short"))).isEqualTo(400);
        assertThat(status(confirmReset(token, EMAIL))).isEqualTo(400);
        assertThat(status(login(EMAIL, OLD))).isEqualTo(200);
        assertThat(status(confirmReset(token, NEW))).isEqualTo(200);
    }

    @Test
    void expiredUnknownAndDeactivatedAccountTokensAreRejected() throws Exception {
        requestReset(EMAIL);
        String token = mail.sent.get(0).token();
        jdbc.update("UPDATE password_reset_token SET expires_at = now() - interval '1 minute'");
        assertThat(status(confirmReset(token, NEW))).isEqualTo(400);
        assertThat(status(confirmReset("not-a-real-token", NEW))).isEqualTo(400);

        mail.sent.clear();
        jdbc.update("DELETE FROM login_attempt");
        requestReset(EMAIL);
        String second = mail.sent.get(0).token();
        jdbc.update("UPDATE app_user SET status = 'DEACTIVATED' WHERE id = ?", userId);
        assertThat(status(confirmReset(second, NEW))).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id = ?", String.class, userId))
            .matches(h -> encoder.matches(OLD, h));
    }

    @Test
    void successfulResetClearsALoginLockout() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(status(login(EMAIL, "wrong"))).isEqualTo(401);
        }
        assertThat(status(login(EMAIL, OLD))).isEqualTo(429);

        requestReset(EMAIL);
        assertThat(status(confirmReset(mail.sent.get(0).token(), NEW))).isEqualTo(200);
        assertThat(status(login(EMAIL, NEW))).isEqualTo(200);
    }

    // ---- authenticated change ----

    @Test
    void changeRevokesOtherSessionsButKeepsTheCurrentOne() throws Exception {
        Cookie current = signIn(OLD);
        Cookie other = signIn(OLD);
        assertThat(meStatus(other)).isEqualTo(200);

        MvcResult changed = change(current, OLD, NEW);
        assertThat(status(changed)).isEqualTo(204);

        assertThat(meStatus(current)).isEqualTo(200);
        assertThat(meStatus(other)).isEqualTo(401);
        assertThat(status(login(EMAIL, OLD))).isEqualTo(401);
        assertThat(status(login(EMAIL, NEW))).isEqualTo(200);
        assertThat(mail.sent).hasSize(1);
        assertThat(mail.sent.get(0).subject()).isEqualTo("Your password was changed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'PASSWORD_CHANGED' AND actor_user_id = ?",
            Integer.class, userId)).isEqualTo(1);
    }

    @Test
    void changeRejectsWrongCurrentPasswordWeakAndUnchangedPasswords() throws Exception {
        Cookie session = signIn(OLD);
        assertThat(status(change(session, "not my password", NEW))).isEqualTo(400);
        assertThat(status(change(session, OLD, "short"))).isEqualTo(400);
        assertThat(status(change(session, OLD, OLD))).isEqualTo(400);
        assertThat(status(change(session, OLD, EMAIL))).isEqualTo(400);
        assertThat(status(login(EMAIL, OLD))).isEqualTo(200);
        assertThat(mail.sent).isEmpty();
    }

    @Test
    void repeatedWrongCurrentPasswordsAreThrottled() throws Exception {
        Cookie session = signIn(OLD);
        for (int i = 0; i < 5; i++) {
            assertThat(status(change(session, "wrong-" + i, NEW))).isEqualTo(400);
        }
        assertThat(status(change(session, OLD, NEW))).isEqualTo(429);
    }

    @Test
    void changeRequiresAuthenticationAndCsrf() throws Exception {
        assertThat(status(change(null, OLD, NEW))).isEqualTo(401);

        Cookie session = signIn(OLD);
        MvcResult noCsrf = mvc.perform(post("/api/v1/auth/password/change").cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + OLD + "\",\"newPassword\":\"" + NEW + "\"}")).andReturn();
        assertThat(status(noCsrf)).isEqualTo(403);
        assertThat(status(login(EMAIL, OLD))).isEqualTo(200);
    }

    @Test
    void resetEndpointsRequireCsrf() throws Exception {
        for (String path : new String[] {"request", "confirm"}) {
            MvcResult r = mvc.perform(post("/api/v1/auth/password-reset/" + path)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
            assertThat(status(r)).isEqualTo(403);
        }
        assertThat((String) JsonPath.read(body(requestReset(EMAIL)), "$.message")).startsWith("If an account exists");
    }
}
