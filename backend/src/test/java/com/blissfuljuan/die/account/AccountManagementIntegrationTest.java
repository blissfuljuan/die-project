package com.blissfuljuan.die.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.blissfuljuan.die.PostgresIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

class AccountManagementIntegrationTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct horse battery staple";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;

    UUID adminId;
    UUID instructorId;
    UUID studentId;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM temp_credential");
        jdbc.update("DELETE FROM login_attempt");
        jdbc.update("DELETE FROM app_user");
        adminId = insertUser("admin@example.edu", "ADMIN", null);
        instructorId = insertUser("instructor@example.edu", "INSTRUCTOR", null);
        studentId = insertUser("student@example.edu", "STUDENT", "2024-0001");
    }

    private UUID insertUser(String email, String role, String studentNumber) {
        return jdbc.queryForObject("""
            INSERT INTO app_user (role, status, student_number, last_name, first_name, email, email_verified_at, password_hash)
            VALUES (?, 'ACTIVE', ?, 'Doe', 'Jane', ?, now(), ?) RETURNING id
            """, UUID.class, role, studentNumber, email, encoder.encode(PASSWORD));
    }

    // ---- helpers ----

    private MvcResult login(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")).andReturn();
    }

    private Cookie loginCookie(String email) throws Exception {
        MvcResult r = login(email, PASSWORD);
        assertThat(r.getResponse().getStatus()).as("login " + email).isEqualTo(200);
        return r.getResponse().getHeaders("Set-Cookie").stream()
            .filter(h -> h.startsWith("SESSION="))
            .map(h -> new Cookie("SESSION", h.substring("SESSION=".length(), h.indexOf(';'))))
            .findFirst().orElseThrow();
    }

    private MvcResult send(String method, String url, Cookie cookie, String json) throws Exception {
        var req = switch (method) {
            case "GET" -> get(url);
            case "PATCH" -> patch(url).with(csrf());
            default -> post(url).with(csrf());
        };
        if (cookie != null) req.cookie(cookie);
        if (json != null) req.contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(req).andReturn();
    }

    private static int status(MvcResult r) { return r.getResponse().getStatus(); }
    private static String body(MvcResult r) throws Exception { return r.getResponse().getContentAsString(); }

    private String createInstructorJson(String email) {
        return "{\"role\":\"INSTRUCTOR\",\"lastName\":\"Reyes\",\"firstName\":\"Ana\",\"email\":\"" + email + "\"}";
    }

    // ---- authorization ----

    @Test
    void onlyAdminsMayManageAccounts() throws Exception {
        Cookie instructor = loginCookie("instructor@example.edu");
        Cookie student = loginCookie("student@example.edu");
        String someUser = "/api/v1/users/" + studentId;

        for (Cookie c : List.of(instructor, student)) {
            assertThat(status(send("GET", "/api/v1/users", c, null))).isEqualTo(403);
            assertThat(status(send("GET", someUser, c, null))).isEqualTo(403);
            assertThat(status(send("POST", "/api/v1/users", c, createInstructorJson("x@example.edu")))).isEqualTo(403);
            assertThat(status(send("POST", someUser + "/deactivate", c, null))).isEqualTo(403);
            assertThat(status(send("POST", someUser + "/activate", c, null))).isEqualTo(403);
            assertThat(status(send("POST", someUser + "/reset-credentials", c, null))).isEqualTo(403);
            assertThat(status(send("PATCH", someUser, c, "{\"version\":0,\"role\":\"ADMIN\"}"))).isEqualTo(403);
        }
        assertThat(status(send("GET", "/api/v1/users", null, null))).isEqualTo(401);
    }

    // ---- create ----

    @Test
    void createReturnsTemporaryCredentialOnceAndStoresOnlyItsHash() throws Exception {
        Cookie admin = loginCookie("admin@example.edu");
        MvcResult created = send("POST", "/api/v1/users", admin, createInstructorJson("New.Person@Example.edu"));
        assertThat(status(created)).isEqualTo(201);
        assertThat(created.getResponse().getHeader("Cache-Control")).contains("no-store");

        String secret = JsonPath.read(body(created), "$.temporaryCredential");
        String id = JsonPath.read(body(created), "$.user.id");
        assertThat(secret).hasSize(14);
        assertThat((String) JsonPath.read(body(created), "$.user.status")).isEqualTo("PENDING_ACTIVATION");
        assertThat((String) JsonPath.read(body(created), "$.user.email")).isEqualTo("new.person@example.edu");

        String hash = jdbc.queryForObject("SELECT secret_hash FROM temp_credential WHERE user_id = ?::uuid", String.class, id);
        assertThat(hash).startsWith("$argon2").doesNotContain(secret);

        String fetched = body(send("GET", "/api/v1/users/" + id, admin, null));
        assertThat(fetched).doesNotContain(secret).doesNotContain("temporaryCredential").doesNotContain("password");
    }

    @Test
    void createValidatesRoleSpecificIdentityAndRejectsDuplicates() throws Exception {
        Cookie admin = loginCookie("admin@example.edu");
        assertThat(status(send("POST", "/api/v1/users", admin,
            "{\"role\":\"STUDENT\",\"lastName\":\"A\",\"firstName\":\"B\"}"))).isEqualTo(400);
        assertThat(status(send("POST", "/api/v1/users", admin,
            "{\"role\":\"INSTRUCTOR\",\"lastName\":\"A\",\"firstName\":\"B\"}"))).isEqualTo(400);
        assertThat(status(send("POST", "/api/v1/users", admin,
            "{\"role\":\"WIZARD\",\"lastName\":\"A\",\"firstName\":\"B\",\"email\":\"w@example.edu\"}"))).isEqualTo(400);
        assertThat(status(send("POST", "/api/v1/users", admin, createInstructorJson("instructor@example.edu"))))
            .isEqualTo(409);
        assertThat(status(send("POST", "/api/v1/users", admin,
            "{\"role\":\"STUDENT\",\"studentNumber\":\"2024-0001\",\"lastName\":\"A\",\"firstName\":\"B\"}")))
            .isEqualTo(409);
    }

    @Test
    void listSupportsFiltersAndPaging() throws Exception {
        Cookie admin = loginCookie("admin@example.edu");
        MvcResult r = send("GET", "/api/v1/users?role=INSTRUCTOR&size=10", admin, null);
        assertThat(status(r)).isEqualTo(200);
        assertThat((Integer) JsonPath.read(body(r), "$.total")).isEqualTo(1);
        assertThat((String) JsonPath.read(body(r), "$.items[0].email")).isEqualTo("instructor@example.edu");
        assertThat(status(send("GET", "/api/v1/users?role=WIZARD", admin, null))).isEqualTo(400);
        assertThat((Integer) JsonPath.read(body(send("GET", "/api/v1/users?q=%25", admin, null)), "$.total")).isZero();
    }

    // ---- deactivate / activate ----

    @Test
    void deactivateRevokesExistingSessionsAndBlocksLogin_activateRestoresAccess() throws Exception {
        Cookie admin = loginCookie("admin@example.edu");
        Cookie instructor = loginCookie("instructor@example.edu");
        assertThat(status(send("GET", "/api/v1/auth/me", instructor, null))).isEqualTo(200);

        MvcResult deactivated = send("POST", "/api/v1/users/" + instructorId + "/deactivate", admin, null);
        assertThat(status(deactivated)).isEqualTo(200);
        assertThat((String) JsonPath.read(body(deactivated), "$.status")).isEqualTo("DEACTIVATED");

        assertThat(status(send("GET", "/api/v1/auth/me", instructor, null))).isEqualTo(401);
        assertThat(status(login("instructor@example.edu", PASSWORD))).isEqualTo(401);

        MvcResult activated = send("POST", "/api/v1/users/" + instructorId + "/activate", admin, null);
        assertThat(status(activated)).isEqualTo(200);
        assertThat((String) JsonPath.read(body(activated), "$.user.status")).isEqualTo("ACTIVE");
        assertThat(body(activated)).contains("\"temporaryCredential\":null");
        assertThat(status(login("instructor@example.edu", PASSWORD))).isEqualTo(200);

        Integer audits = jdbc.queryForObject(
            "SELECT count(*) FROM audit_log WHERE action IN ('USER_DEACTIVATED','USER_ACTIVATED') AND actor_user_id = ?",
            Integer.class, adminId);
        assertThat(audits).isEqualTo(2);
    }

    @Test
    void activatingANonDeactivatedAccountIsRejected() throws Exception {
        Cookie admin = loginCookie("admin@example.edu");
        assertThat(status(send("POST", "/api/v1/users/" + instructorId + "/activate", admin, null))).isEqualTo(409);
    }

    @Test
    void adminCannotApplyDestructiveOperationsToSelf() throws Exception {
        Cookie admin = loginCookie("admin@example.edu");
        String self = "/api/v1/users/" + adminId;
        assertThat(status(send("POST", self + "/deactivate", admin, null))).isEqualTo(409);
        assertThat(status(send("POST", self + "/reset-credentials", admin, null))).isEqualTo(409);
        assertThat(status(send("PATCH", self, admin, "{\"version\":0,\"role\":\"INSTRUCTOR\"}"))).isEqualTo(409);
        assertThat(status(send("GET", "/api/v1/auth/me", admin, null))).isEqualTo(200);
    }

    @Test
    void unknownUserIs404() throws Exception {
        Cookie admin = loginCookie("admin@example.edu");
        String missing = "/api/v1/users/" + UUID.randomUUID();
        assertThat(status(send("GET", missing, admin, null))).isEqualTo(404);
        assertThat(status(send("POST", missing + "/deactivate", admin, null))).isEqualTo(404);
    }

    // ---- role assignment / update ----

    @Test
    void roleChangeRevokesSessionsAndIsAudited() throws Exception {
        Cookie admin = loginCookie("admin@example.edu");
        Cookie instructor = loginCookie("instructor@example.edu");
        long version = jdbc.queryForObject("SELECT version FROM app_user WHERE id = ?", Long.class, instructorId);

        MvcResult r = send("PATCH", "/api/v1/users/" + instructorId, admin,
            "{\"version\":" + version + ",\"role\":\"ADMIN\"}");
        assertThat(status(r)).isEqualTo(200);
        assertThat((String) JsonPath.read(body(r), "$.role")).isEqualTo("ADMIN");

        assertThat(status(send("GET", "/api/v1/auth/me", instructor, null))).isEqualTo(401);
        Cookie fresh = loginCookie("instructor@example.edu");
        assertThat(status(send("GET", "/api/v1/users", fresh, null))).isEqualTo(200);

        Integer audits = jdbc.queryForObject(
            "SELECT count(*) FROM audit_log WHERE action = 'ROLE_CHANGED' AND resource_id = ?",
            Integer.class, instructorId.toString());
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void staleVersionIsRejectedAndStudentRoleNeedsStudentNumber() throws Exception {
        Cookie admin = loginCookie("admin@example.edu");
        String url = "/api/v1/users/" + instructorId;
        assertThat(status(send("PATCH", url, admin, "{\"version\":999,\"firstName\":\"X\"}"))).isEqualTo(409);
        assertThat(status(send("PATCH", url, admin, "{\"firstName\":\"X\"}"))).isEqualTo(400);

        long version = jdbc.queryForObject("SELECT version FROM app_user WHERE id = ?", Long.class, instructorId);
        assertThat(status(send("PATCH", url, admin, "{\"version\":" + version + ",\"role\":\"STUDENT\"}"))).isEqualTo(400);
    }

    @Test
    void emailOfActivatedAccountCannotBeChangedByPatch() throws Exception {
        Cookie admin = loginCookie("admin@example.edu");
        long version = jdbc.queryForObject("SELECT version FROM app_user WHERE id = ?", Long.class, instructorId);
        assertThat(status(send("PATCH", "/api/v1/users/" + instructorId, admin,
            "{\"version\":" + version + ",\"email\":\"other@example.edu\"}"))).isEqualTo(409);
    }

    // ---- credential reset ----

    @Test
    void resetCredentialsRevokesSessionsInvalidatesPasswordAndIssuesSingleLiveCredential() throws Exception {
        Cookie admin = loginCookie("admin@example.edu");
        Cookie student = loginCookie("student@example.edu");

        MvcResult first = send("POST", "/api/v1/users/" + studentId + "/reset-credentials", admin, null);
        assertThat(status(first)).isEqualTo(200);
        assertThat(first.getResponse().getHeader("Cache-Control")).contains("no-store");
        String firstSecret = JsonPath.read(body(first), "$.temporaryCredential");
        assertThat((String) JsonPath.read(body(first), "$.user.status")).isEqualTo("PENDING_ACTIVATION");

        assertThat(status(send("GET", "/api/v1/auth/me", student, null))).isEqualTo(401);
        assertThat(status(login("student@example.edu", PASSWORD))).isEqualTo(401);

        MvcResult second = send("POST", "/api/v1/users/" + studentId + "/reset-credentials", admin, null);
        String secondSecret = JsonPath.read(body(second), "$.temporaryCredential");
        assertThat(secondSecret).isNotEqualTo(firstSecret);

        Integer live = jdbc.queryForObject(
            "SELECT count(*) FROM temp_credential WHERE user_id = ? AND invalidated_at IS NULL", Integer.class, studentId);
        assertThat(live).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id = ?", String.class, studentId)).isNull();
    }

    @Test
    void resetOfDeactivatedAccountIsRejected() throws Exception {
        Cookie admin = loginCookie("admin@example.edu");
        send("POST", "/api/v1/users/" + studentId + "/deactivate", admin, null);
        assertThat(status(send("POST", "/api/v1/users/" + studentId + "/reset-credentials", admin, null))).isEqualTo(409);
    }
}
