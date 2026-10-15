package io.die.evaluator.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import io.die.evaluator.PostgresIntegrationTest;
import io.die.evaluator.support.CapturingEmailSender;
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
class ActivationIntegrationTest extends PostgresIntegrationTest {

    private static final String TEMP = "Temp-Secret-123";
    private static final String NEW_PASSWORD = "a long new passphrase";
    private static final String ADMIN_PASSWORD = "admin passphrase 123";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired CapturingEmailSender mail;

    @BeforeEach
    void clean() {
        mail.sent.clear();
        jdbc.update("DELETE FROM temp_credential");
        jdbc.update("DELETE FROM email_verification_token");
        jdbc.update("DELETE FROM login_attempt");
        jdbc.update("DELETE FROM app_user");
    }

    // ---- helpers ----

    /** Student awaiting activation (as created by a roster import), with a known temporary credential. */
    private UUID pendingStudent(String studentNumber) {
        UUID id = jdbc.queryForObject("""
            INSERT INTO app_user (role, status, student_number, last_name, first_name)
            VALUES ('STUDENT', 'PENDING_ACTIVATION', ?, 'Cruz', 'Maria') RETURNING id
            """, UUID.class, studentNumber);
        jdbc.update("INSERT INTO temp_credential (user_id, secret_hash, expires_at) VALUES (?, ?, now() + interval '14 days')",
            id, encoder.encode(TEMP));
        return id;
    }

    private UUID activeUser(String email, String role, String password) {
        return jdbc.queryForObject("""
            INSERT INTO app_user (role, status, last_name, first_name, email, email_verified_at, password_hash)
            VALUES (?, 'ACTIVE', 'Doe', 'Jane', ?, now(), ?) RETURNING id
            """, UUID.class, role, email, encoder.encode(password));
    }

    private MvcResult postJson(String url, Cookie cookie, String json) throws Exception {
        MockHttpServletRequestBuilder req = post(url).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json);
        if (cookie != null) req.cookie(cookie);
        return mvc.perform(req).andReturn();
    }

    private MvcResult check(String identifier, String secret) throws Exception {
        return postJson("/api/v1/auth/activation/check", null,
            "{\"identifier\":\"" + identifier + "\",\"temporaryCredential\":\"" + secret + "\"}");
    }

    private MvcResult begin(String identifier, String secret, String email, String password) throws Exception {
        return postJson("/api/v1/auth/activation/begin", null,
            "{\"identifier\":\"" + identifier + "\",\"temporaryCredential\":\"" + secret + "\",\"email\":\""
                + email + "\",\"newPassword\":\"" + password + "\"}");
    }

    private MvcResult verify(String token) throws Exception {
        return postJson("/api/v1/auth/activation/verify", null, "{\"token\":\"" + token + "\"}");
    }

    private MvcResult login(String email, String password) throws Exception {
        return postJson("/api/v1/auth/login", null, "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    private Cookie sessionOf(MvcResult login) {
        return login.getResponse().getHeaders("Set-Cookie").stream()
            .filter(h -> h.startsWith("SESSION="))
            .map(h -> new Cookie("SESSION", h.substring("SESSION=".length(), h.indexOf(';'))))
            .findFirst().orElseThrow();
    }

    private static int status(MvcResult r) { return r.getResponse().getStatus(); }
    private static String body(MvcResult r) throws Exception { return r.getResponse().getContentAsString(); }

    private String invalidReason(UUID userId) {
        return jdbc.queryForObject(
            "SELECT invalid_reason FROM temp_credential WHERE user_id = ? ORDER BY created_at DESC LIMIT 1", String.class, userId);
    }

    // ---- the brief's flow ----

    @Test
    void studentActivatesWithStudentIdThenVerifiesEmailThenSignsInWithEmail() throws Exception {
        UUID id = pendingStudent("2024-0100");

        MvcResult checked = check("2024-0100", TEMP);
        assertThat(status(checked)).isEqualTo(200);
        assertThat(checked.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat((String) JsonPath.read(body(checked), "$.firstName")).isEqualTo("Maria");
        assertThat((Boolean) JsonPath.read(body(checked), "$.emailVerified")).isFalse();

        MvcResult begun = begin("2024-0100", TEMP, "Maria.Cruz@Example.edu", NEW_PASSWORD);
        assertThat(status(begun)).isEqualTo(200);
        assertThat((String) JsonPath.read(body(begun), "$.status")).isEqualTo("VERIFICATION_REQUIRED");

        // Not usable until the email is verified; the temporary credential is still the proof of identity.
        assertThat(status(login("maria.cruz@example.edu", NEW_PASSWORD))).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT status FROM app_user WHERE id = ?", String.class, id))
            .isEqualTo("PENDING_ACTIVATION");
        assertThat(invalidReason(id)).isNull();

        assertThat(mail.sent).hasSize(1);
        CapturingEmailSender.Message message = mail.sent.get(0);
        assertThat(message.to()).isEqualTo("maria.cruz@example.edu");
        String token = message.token();
        String storedHash = jdbc.queryForObject("SELECT token_hash FROM email_verification_token WHERE user_id = ?", String.class, id);
        assertThat(storedHash).hasSize(64).isNotEqualTo(token);

        MvcResult verified = verify(token);
        assertThat(status(verified)).isEqualTo(200);
        assertThat((String) JsonPath.read(body(verified), "$.status")).isEqualTo("ACTIVE");
        assertThat(invalidReason(id)).isEqualTo("ACTIVATED");

        MvcResult signedIn = login("maria.cruz@example.edu", NEW_PASSWORD);
        assertThat(status(signedIn)).isEqualTo(200);
        assertThat(body(signedIn)).contains("\"role\":\"STUDENT\"");

        // Temporary credential and verification link are single-use.
        assertThat(status(check("2024-0100", TEMP))).isEqualTo(401);
        assertThat(status(verify(token))).isEqualTo(400);

        Integer audits = jdbc.queryForObject(
            "SELECT count(*) FROM audit_log WHERE resource_id = ? AND action IN ('ACTIVATION_STARTED','ACCOUNT_ACTIVATED')",
            Integer.class, id.toString());
        assertThat(audits).isEqualTo(2);
    }

    @Test
    void adminCreatedInstructorActivatesViaEmailAndTemporaryCredential() throws Exception {
        activeUser("admin@example.edu", "ADMIN", ADMIN_PASSWORD);
        Cookie admin = sessionOf(login("admin@example.edu", ADMIN_PASSWORD));
        MvcResult created = postJson("/api/v1/users", admin,
            "{\"role\":\"INSTRUCTOR\",\"lastName\":\"Reyes\",\"firstName\":\"Ana\",\"email\":\"ana@example.edu\"}");
        assertThat(status(created)).isEqualTo(201);
        String temp = JsonPath.read(body(created), "$.temporaryCredential");

        // Staff identify themselves by email; the address still has to be verified.
        MvcResult begun = begin("ana@example.edu", temp, "ana@example.edu", NEW_PASSWORD);
        assertThat((String) JsonPath.read(body(begun), "$.status")).isEqualTo("VERIFICATION_REQUIRED");
        assertThat(status(verify(mail.sent.get(0).token()))).isEqualTo(200);
        assertThat(status(login("ana@example.edu", NEW_PASSWORD))).isEqualTo(200);
    }

    @Test
    void resetUserKeepingVerifiedEmailIsActiveImmediatelyWithoutNewMail() throws Exception {
        activeUser("admin@example.edu", "ADMIN", ADMIN_PASSWORD);
        UUID teacher = activeUser("teacher@example.edu", "INSTRUCTOR", "old passphrase 123");
        Cookie admin = sessionOf(login("admin@example.edu", ADMIN_PASSWORD));
        MvcResult reset = postJson("/api/v1/users/" + teacher + "/reset-credentials", admin, "{}");
        String temp = JsonPath.read(body(reset), "$.temporaryCredential");

        assertThat(status(login("teacher@example.edu", "old passphrase 123"))).isEqualTo(401);

        MvcResult begun = begin("teacher@example.edu", temp, "teacher@example.edu", NEW_PASSWORD);
        assertThat((String) JsonPath.read(body(begun), "$.status")).isEqualTo("ACTIVE");
        assertThat(mail.sent).isEmpty();
        assertThat(status(login("teacher@example.edu", NEW_PASSWORD))).isEqualTo(200);
    }

    @Test
    void changingEmailBeforeVerifyingVoidsTheEarlierLink() throws Exception {
        UUID id = pendingStudent("2024-0101");
        begin("2024-0101", TEMP, "typo@example.edu", NEW_PASSWORD);
        begin("2024-0101", TEMP, "right@example.edu", NEW_PASSWORD);
        assertThat(mail.sent).hasSize(2);

        assertThat(status(verify(mail.sent.get(0).token()))).isEqualTo(400);
        assertThat(status(verify(mail.sent.get(1).token()))).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT email FROM app_user WHERE id = ?", String.class, id))
            .isEqualTo("right@example.edu");
    }

    @Test
    void replacingAVerifiedEmailIsAuditedWithBothAddressesAndWarnsThePreviousOne() throws Exception {
        // Account reset by an admin: email stays verified, password cleared, new temporary credential.
        UUID id = jdbc.queryForObject("""
            INSERT INTO app_user (role, status, last_name, first_name, email, email_verified_at)
            VALUES ('INSTRUCTOR', 'PENDING_ACTIVATION', 'Reyes', 'Ana', 'ana@example.edu', now()) RETURNING id
            """, UUID.class);
        jdbc.update("INSERT INTO temp_credential (user_id, secret_hash, expires_at) VALUES (?, ?, now() + interval '14 days')",
            id, encoder.encode(TEMP));

        MvcResult begun = begin("ana@example.edu", TEMP, "someone.else@example.edu", NEW_PASSWORD);
        assertThat((String) JsonPath.read(body(begun), "$.status")).isEqualTo("VERIFICATION_REQUIRED");

        assertThat(mail.sent).extracting(CapturingEmailSender.Message::to)
            .containsExactlyInAnyOrder("ana@example.edu", "someone.else@example.edu");
        String details = jdbc.queryForObject(
            "SELECT details::text FROM audit_log WHERE action = 'ACTIVATION_STARTED' AND resource_id = ?",
            String.class, id.toString());
        assertThat(details).contains("ana@example.edu").contains("someone.else@example.edu");
    }

    // ---- failure handling ----

    @Test
    void wrongSecretUnknownAccountAndExpiredCredentialAreIndistinguishable() throws Exception {
        UUID expired = pendingStudent("2024-0102");
        jdbc.update("UPDATE temp_credential SET expires_at = now() - interval '1 minute' WHERE user_id = ?", expired);
        pendingStudent("2024-0103");

        String wrong = body(check("2024-0103", "not-the-secret"));
        String unknown = body(check("9999-9999", TEMP));
        MvcResult expiredResult = check("2024-0102", TEMP);

        assertThat(status(expiredResult)).isEqualTo(401);
        assertThat(wrong).isEqualTo(unknown).isEqualTo(body(expiredResult));
        assertThat(invalidReason(expired)).isEqualTo("EXPIRED");
    }

    @Test
    void repeatedWrongSecretsLockTheCredentialUntilAnAdminResetsIt() throws Exception {
        UUID id = pendingStudent("2024-0104");
        for (int i = 0; i < 5; i++) {
            assertThat(status(check("2024-0104", "wrong-" + i))).isEqualTo(401);
        }
        assertThat(invalidReason(id)).isEqualTo("LOCKED");
        // Even the correct secret no longer works (credential invalid, and the identifier is throttled).
        int after = status(check("2024-0104", TEMP));
        assertThat(after).isIn(401, 429);
        assertThat(status(begin("2024-0104", TEMP, "x@example.edu", NEW_PASSWORD))).isIn(401, 429);
    }

    @Test
    void weakPasswordIsRejectedWithoutConsumingTheCredential() throws Exception {
        UUID id = pendingStudent("2024-0105");
        assertThat(status(begin("2024-0105", TEMP, "s@example.edu", "short"))).isEqualTo(400);
        assertThat(status(begin("2024-0105", TEMP, "s@example.edu", "s@example.edu"))).isEqualTo(400);
        assertThat(invalidReason(id)).isNull();
        assertThat(jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id = ?", String.class, id)).isNull();
        assertThat(status(begin("2024-0105", TEMP, "s@example.edu", NEW_PASSWORD))).isEqualTo(200);
    }

    @Test
    void emailAlreadyUsedByAnotherAccountIsRejectedAndLeavesStateUntouched() throws Exception {
        activeUser("taken@example.edu", "INSTRUCTOR", "whatever passphrase");
        UUID id = pendingStudent("2024-0106");
        assertThat(status(begin("2024-0106", TEMP, "taken@example.edu", NEW_PASSWORD))).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT email FROM app_user WHERE id = ?", String.class, id)).isNull();
        assertThat(mail.sent).isEmpty();
    }

    @Test
    void expiredOrUnknownVerificationTokensAreRejected() throws Exception {
        UUID id = pendingStudent("2024-0107");
        begin("2024-0107", TEMP, "late@example.edu", NEW_PASSWORD);
        String token = mail.sent.get(0).token();
        jdbc.update("UPDATE email_verification_token SET expires_at = now() - interval '1 minute' WHERE user_id = ?", id);

        assertThat(status(verify(token))).isEqualTo(400);
        assertThat(status(verify("totally-unknown-token"))).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT status FROM app_user WHERE id = ?", String.class, id))
            .isEqualTo("PENDING_ACTIVATION");
    }

    @Test
    void deactivatedAccountsCannotActivate() throws Exception {
        UUID id = pendingStudent("2024-0108");
        jdbc.update("UPDATE app_user SET status = 'DEACTIVATED' WHERE id = ?", id);
        assertThat(status(check("2024-0108", TEMP))).isEqualTo(401);
    }

    @Test
    void activationEndpointsRequireCsrfToken() throws Exception {
        pendingStudent("2024-0109");
        for (String path : new String[] {"check", "begin", "verify"}) {
            mvc.perform(post("/api/v1/auth/activation/" + path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        }
    }
}
