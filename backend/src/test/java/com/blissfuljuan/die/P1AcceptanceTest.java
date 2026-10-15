package com.blissfuljuan.die;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.blissfuljuan.die.support.CapturingEmailSender;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * MVP acceptance criteria 1-4 (brief section 10), exercised end to end through the public HTTP API only.
 * The single piece of seeded data is the first administrator, which production creates with BootstrapAdmin.
 *
 *  1. An administrator can create or import student accounts.
 *  2. Students can activate their accounts and securely sign in.
 *  3. An instructor can create a class and manage its student roster.
 *  4. Individual and group projects can be established.
 */
@Import(CapturingEmailSender.Config.class)
class P1AcceptanceTest extends PostgresIntegrationTest {

    private static final String ADMIN_PASSWORD = "the first administrator";
    private static final String NEW_PASSWORD = "a brand new passphrase";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired CapturingEmailSender mail;

    @BeforeEach
    void bootstrapAdmin() {
        mail.sent.clear();
        jdbc.update("""
            INSERT INTO app_user (role, status, last_name, first_name, email, email_verified_at, password_hash)
            VALUES ('ADMIN', 'ACTIVE', 'Root', 'Ada', 'admin@school.edu', now(), ?)
            """, encoder.encode(ADMIN_PASSWORD));
    }

    // ---- HTTP helpers ----

    private MvcResult send(Cookie cookie, HttpMethod method, String path, String json) throws Exception {
        MockHttpServletRequestBuilder req = request(method, path);
        if (method != HttpMethod.GET) req.with(csrf());
        if (cookie != null) req.cookie(cookie);
        if (json != null) req.contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(req).andReturn();
    }

    private static int status(MvcResult r) { return r.getResponse().getStatus(); }
    private static String body(MvcResult r) throws Exception { return r.getResponse().getContentAsString(); }

    private MvcResult ok(MvcResult r) throws Exception {
        assertThat(status(r)).as(r.getRequest().getMethod() + " " + r.getRequest().getRequestURI() + " -> " + body(r)).isBetween(200, 299);
        return r;
    }

    private Cookie signIn(String email, String password) throws Exception {
        MvcResult r = send(null, HttpMethod.POST, "/api/v1/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
        assertThat(status(r)).as("sign in " + email).isEqualTo(200);
        return r.getResponse().getHeaders("Set-Cookie").stream()
            .filter(h -> h.startsWith("SESSION="))
            .map(h -> new Cookie("SESSION", h.substring("SESSION=".length(), h.indexOf(';'))))
            .findFirst().orElseThrow();
    }

    /** The user-facing activation flow: identify with the temporary credential, set email + password, confirm the email link. */
    private void activate(String identifier, String temporaryCredential, String email) throws Exception {
        ok(send(null, HttpMethod.POST, "/api/v1/auth/activation/check",
            "{\"identifier\":\"" + identifier + "\",\"temporaryCredential\":\"" + temporaryCredential + "\"}"));
        MvcResult begun = ok(send(null, HttpMethod.POST, "/api/v1/auth/activation/begin",
            "{\"identifier\":\"" + identifier + "\",\"temporaryCredential\":\"" + temporaryCredential + "\",\"email\":\""
                + email + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}"));
        assertThat((String) JsonPath.read(body(begun), "$.status")).isEqualTo("VERIFICATION_REQUIRED");
        CapturingEmailSender.Message verification = mail.sent.stream().filter(m -> m.to().equals(email)).reduce((first, second) -> second).orElseThrow();
        MvcResult verified = ok(send(null, HttpMethod.POST, "/api/v1/auth/activation/verify", "{\"token\":\"" + verification.token() + "\"}"));
        assertThat((String) JsonPath.read(body(verified), "$.status")).isEqualTo("ACTIVE");
    }

    /** JsonPath filter expressions always yield a list; return its single element. */
    private static String first(String json, String filterPath) {
        List<Object> found = JsonPath.read(json, filterPath);
        assertThat(found).as(filterPath).hasSize(1);
        return found.get(0).toString();
    }

    private UUID id(MvcResult r, String path) throws Exception {
        return UUID.fromString(JsonPath.read(body(r), path));
    }

    // ---- the scenario ----

    @Test
    void criteria1To4WorkEndToEndThroughTheApi() throws Exception {
        // ===== Criterion 1a: the administrator creates accounts =====
        Cookie admin = signIn("admin@school.edu", ADMIN_PASSWORD);
        MvcResult instructorAccount = ok(send(admin, HttpMethod.POST, "/api/v1/users",
            "{\"role\":\"INSTRUCTOR\",\"lastName\":\"Reyes\",\"firstName\":\"Ivy\",\"email\":\"ivy@school.edu\"}"));
        String instructorTemp = JsonPath.read(body(instructorAccount), "$.temporaryCredential");
        MvcResult studentAccount = ok(send(admin, HttpMethod.POST, "/api/v1/users",
            "{\"role\":\"STUDENT\",\"studentNumber\":\"2025-0001\",\"lastName\":\"Cruz\",\"firstName\":\"Ana\"}"));
        String studentTemp = JsonPath.read(body(studentAccount), "$.temporaryCredential");
        assertThat((String) JsonPath.read(body(studentAccount), "$.user.status")).isEqualTo("PENDING_ACTIVATION");

        // ===== Criterion 2: activation and secure sign-in =====
        activate("ivy@school.edu", instructorTemp, "ivy@school.edu");
        MvcResult login = send(null, HttpMethod.POST, "/api/v1/auth/login", "{\"email\":\"ivy@school.edu\",\"password\":\"" + NEW_PASSWORD + "\"}");
        assertThat(status(login)).isEqualTo(200);
        String cookieHeader = login.getResponse().getHeaders("Set-Cookie").stream().filter(h -> h.startsWith("SESSION=")).findFirst().orElseThrow();
        assertThat(cookieHeader).contains("HttpOnly").contains("Secure").contains("SameSite=Lax");
        assertThat(status(send(null, HttpMethod.POST, "/api/v1/auth/login", "{\"email\":\"ivy@school.edu\",\"password\":\"wrong\"}")))
            .as("wrong password").isEqualTo(401);
        assertThat(status(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"ivy@school.edu\",\"password\":\"" + NEW_PASSWORD + "\"}")).andReturn())).as("CSRF protection").isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM app_user WHERE email = 'ivy@school.edu'", String.class)).startsWith("$argon2");
        Cookie instructor = signIn("ivy@school.edu", NEW_PASSWORD);

        // ===== Criterion 3: the instructor creates a class and manages the roster =====
        MvcResult cls = ok(send(instructor, HttpMethod.POST, "/api/v1/classes",
            "{\"academicYear\":\"2025-2026\",\"semester\":\"1st\",\"subjectCode\":\"CS101\",\"subjectName\":\"Software Engineering\","
                + "\"section\":\"A\",\"teamFormation\":\"STUDENT_FORMED\",\"teamMinSize\":2,\"teamMaxSize\":3}"));
        UUID classId = id(cls, "$.id");
        String classUrl = "/api/v1/classes/" + classId;
        // The admin created this never-activated account, so the admin places it (an instructor may not claim it).
        ok(send(admin, HttpMethod.POST, classUrl + "/enrollments", "{\"studentNumber\":\"2025-0001\",\"program\":\"BSCS\",\"section\":\"1A\"}"));

        String csv = "Student ID,Last Name,First Name,Middle Name,Email,Program,Section\n"
            + "2025-0002,Lim,Noel,,noel@school.edu,BSCS,1A\n"
            + "2025-0003,Poe,Quin,,quin@school.edu,BSCS,1A\n"
            + "2025-0004,Tan,Lea,,,BSCS,1B\n"
            + "2025-0001,Cruz,Ana,,,BSCS,1A\n";           // already enrolled -> skipped, not duplicated
        MvcResult uploaded = mvc.perform(multipart(classUrl + "/roster-imports")
            .file(new MockMultipartFile("file", "roster.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .with(csrf()).cookie(instructor)).andReturn();
        ok(uploaded);
        UUID importId = id(uploaded, "$.id");
        assertThat((Integer) JsonPath.read(body(uploaded), "$.summary.create")).isEqualTo(3);
        assertThat((Integer) JsonPath.read(body(uploaded), "$.summary.skipDuplicate")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(body(uploaded), "$.summary.unresolvedConflicts")).isZero();
        MvcResult committed = ok(send(instructor, HttpMethod.POST, "/api/v1/roster-imports/" + importId + "/commit", null));
        assertThat((Integer) JsonPath.read(body(committed), "$.created")).isEqualTo(3);
        List<String> numbers = JsonPath.read(body(committed), "$.credentials[*].studentNumber");
        assertThat(numbers).containsExactlyInAnyOrder("2025-0002", "2025-0003", "2025-0004");
        String noelTemp = first(body(committed), "$.credentials[?(@.studentNumber=='2025-0002')].temporaryCredential");
        String quinTemp = first(body(committed), "$.credentials[?(@.studentNumber=='2025-0003')].temporaryCredential");

        MvcResult roster = ok(send(instructor, HttpMethod.GET, classUrl + "/enrollments", null));
        assertThat((List<String>) JsonPath.read(body(roster), "$[*].studentNumber"))
            .containsExactlyInAnyOrder("2025-0001", "2025-0002", "2025-0003", "2025-0004");

        // ===== Criterion 2 for students: both creation paths (admin-created and imported) =====
        activate("2025-0001", studentTemp, "ana@school.edu");
        activate("2025-0002", noelTemp, "noel@school.edu");
        activate("2025-0003", quinTemp, "quin@school.edu");
        Cookie ana = signIn("ana@school.edu", NEW_PASSWORD);
        Cookie noel = signIn("noel@school.edu", NEW_PASSWORD);
        Cookie quin = signIn("quin@school.edu", NEW_PASSWORD);
        assertThat((List<String>) JsonPath.read(body(ok(send(ana, HttpMethod.GET, "/api/v1/classes", null))), "$.items[*].subjectCode")).containsExactly("CS101");
        assertThat(status(send(ana, HttpMethod.GET, classUrl + "/enrollments", null))).as("students do not see the roster").isEqualTo(403);
        assertThat(status(send(ana, HttpMethod.POST, "/api/v1/users", "{\"role\":\"ADMIN\",\"lastName\":\"X\",\"firstName\":\"Y\",\"email\":\"x@school.edu\"}")))
            .as("students cannot create accounts").isEqualTo(403);

        // ===== Criterion 4a: a group project (student-formed team with an invitation) =====
        MvcResult team = ok(send(noel, HttpMethod.POST, classUrl + "/projects", "{\"kind\":\"TEAM\",\"title\":\"Capstone\",\"teamName\":\"Team Noel\"}"));
        UUID teamId = id(team, "$.id");
        String quinId = first(body(ok(send(noel, HttpMethod.GET, classUrl + "/eligible-classmates", null))), "$[?(@.firstName=='Quin')].id");
        MvcResult invitation = ok(send(noel, HttpMethod.POST, "/api/v1/projects/" + teamId + "/invitations", "{\"studentId\":\"" + quinId + "\"}"));
        UUID invitationId = id(invitation, "$.id");
        MvcResult joined = ok(send(quin, HttpMethod.POST, "/api/v1/invitations/" + invitationId + "/accept", null));
        assertThat((List<String>) JsonPath.read(body(joined), "$.members[*].firstName")).containsExactlyInAnyOrder("Noel", "Quin");
        assertThat((Boolean) JsonPath.read(body(joined), "$.meetsMinSize")).isTrue();

        // ===== Criterion 4b: an individual project assigned by the instructor =====
        UUID lea = jdbc.queryForObject("SELECT id FROM app_user WHERE student_number = '2025-0004'", UUID.class);
        MvcResult solo = ok(send(instructor, HttpMethod.POST, classUrl + "/projects",
            "{\"kind\":\"INDIVIDUAL\",\"title\":\"Personal project\",\"studentId\":\"" + lea + "\"}"));
        assertThat((List<String>) JsonPath.read(body(solo), "$.members[*].firstName")).containsExactly("Lea");

        // Everyone sees exactly what they should.
        assertThat((List<String>) JsonPath.read(body(ok(send(instructor, HttpMethod.GET, classUrl + "/projects", null))), "$[*].title"))
            .containsExactlyInAnyOrder("Capstone", "Personal project");
        assertThat((List<String>) JsonPath.read(body(ok(send(quin, HttpMethod.GET, classUrl + "/projects", null))), "$[*].title")).containsExactly("Capstone");
        assertThat((List<Object>) JsonPath.read(body(ok(send(ana, HttpMethod.GET, classUrl + "/projects", null))), "$")).isEmpty();
        assertThat(status(send(ana, HttpMethod.GET, "/api/v1/projects/" + teamId, null))).as("classmate off the team").isEqualTo(404);

        // The paper trail exists and never contains secrets.
        List<String> actions = jdbc.queryForList("SELECT DISTINCT action FROM audit_log", String.class);
        assertThat(actions).contains("USER_CREATED", "CLASS_CREATED", "STUDENT_ENROLLED", "ROSTER_IMPORT_COMMITTED",
            "ACCOUNT_ACTIVATED", "PROJECT_CREATED", "TEAM_INVITATION_SENT", "TEAM_INVITATION_ACCEPTED", "LOGIN_SUCCEEDED");
        String allAudit = String.join(" ", jdbc.queryForList("SELECT details::text FROM audit_log", String.class));
        for (String secret : List.of(instructorTemp, studentTemp, noelTemp, quinTemp, NEW_PASSWORD, ADMIN_PASSWORD)) {
            assertThat(allAudit).doesNotContain(secret);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM temp_credential WHERE invalidated_at IS NULL AND user_id IN "
            + "(SELECT id FROM app_user WHERE status = 'ACTIVE')", Integer.class)).as("activation retires temporary credentials").isZero();
    }
}
