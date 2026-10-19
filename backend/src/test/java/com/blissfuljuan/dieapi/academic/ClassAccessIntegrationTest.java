package com.blissfuljuan.dieapi.academic;

import com.blissfuljuan.dieapi.PostgresIntegrationTest;
import com.blissfuljuan.dieapi.academic.view.ClassView;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Classes + enrollment, with the negative-authorization matrix: every actor that must NOT be able
 * to see or change a class is tried against every endpoint, and the data is checked to be unchanged.
 */
class ClassAccessIntegrationTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct horse battery staple";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;

    // users
    UUID admin, i1, i2, i3, goneInstructor, s1, s2, s3, goneStudent;
    // classes: C1 taught by i1 with s1 enrolled; C2 taught by i2 with s2 enrolled
    UUID c1, c2;
    final Map<UUID, Cookie> sessions = new HashMap<>();

    @BeforeEach
    void seed() throws Exception {
        sessions.clear();
        jdbc.update("DELETE FROM login_attempt");
        jdbc.update("DELETE FROM enrollment");
        jdbc.update("DELETE FROM academic_class");
        jdbc.update("DELETE FROM app_user");
        admin = user("admin@x.edu", "ADMIN", "ACTIVE", null);
        i1 = user("i1@x.edu", "COORDINATOR", "ACTIVE", null);
        i2 = user("i2@x.edu", "COORDINATOR", "ACTIVE", null);
        i3 = user("i3@x.edu", "COORDINATOR", "ACTIVE", null);
        goneInstructor = user("gone-i@x.edu", "COORDINATOR", "DEACTIVATED", null);
        s1 = user("s1@x.edu", "PARTICIPANT", "ACTIVE", "S-1");
        s2 = user("s2@x.edu", "PARTICIPANT", "ACTIVE", "S-2");
        s3 = user("s3@x.edu", "PARTICIPANT", "ACTIVE", "S-3");
        goneStudent = user("gone-s@x.edu", "PARTICIPANT", "DEACTIVATED", "S-9");
        c1 = clazz("CS101", "A", i1, s1);
        c2 = clazz("CS102", "B", i2, s2);
    }

    // ---- fixtures / helpers ----

    private UUID user(String email, String role, String status, String studentNumber) {
        return jdbc.queryForObject("""
            INSERT INTO app_user (role, status, student_number, last_name, first_name, email, email_verified_at, password_hash)
            VALUES (?, ?, ?, ?, 'Test', ?, now(), ?) RETURNING id
            """, UUID.class, role, status, studentNumber, email.substring(0, email.indexOf('@')), email,
            encoder.encode(PASSWORD));
    }

    private UUID clazz(String code, String section, UUID instructor, UUID student) {
        UUID id = jdbc.queryForObject("""
            WITH c AS (INSERT INTO academic_class (academic_year, semester, subject_code, subject_name, section, owner_id)
            VALUES ('2025-2026', '1st', ?, 'Subject ' || ?, ?, ?) RETURNING id)
            INSERT INTO class_instructor (class_id, user_id) SELECT id, ? FROM c RETURNING class_id
            """, UUID.class, code, code, section, instructor, instructor);
        jdbc.update("INSERT INTO enrollment (class_id, student_id) VALUES (?, ?)", id, student);
        return id;
    }

    private Cookie as(UUID user) throws Exception {
        Cookie existing = sessions.get(user);
        if (existing != null) return existing;
        String email = jdbc.queryForObject("SELECT email FROM app_user WHERE id = ?", String.class, user);
        MvcResult r = mvc.perform(request(HttpMethod.POST, "/api/v1/auth/login").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")).andReturn();
        assertThat(r.getResponse().getStatus()).as("login " + email).isEqualTo(200);
        Cookie cookie = r.getResponse().getHeaders("Set-Cookie").stream()
            .filter(h -> h.startsWith("SESSION="))
            .map(h -> new Cookie("SESSION", h.substring("SESSION=".length(), h.indexOf(';'))))
            .findFirst().orElseThrow();
        sessions.put(user, cookie);
        return cookie;
    }

    /** Sends a request as {@code actor} (null = anonymous). */
    private MvcResult call(UUID actor, HttpMethod method, String path, String json) throws Exception {
        MockHttpServletRequestBuilder req = request(method, path);
        if (method != HttpMethod.GET) req.with(csrf());
        if (actor != null) req.cookie(as(actor));
        if (json != null) req.contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(req).andReturn();
    }

    private static int status(MvcResult r) { return r.getResponse().getStatus(); }
    private static String body(MvcResult r) throws Exception { return r.getResponse().getContentAsString(); }

    private List<String> listedCodes(UUID actor) throws Exception {
        MvcResult r = call(actor, HttpMethod.GET, "/api/v1/workspaces?size=100", null);
        assertThat(status(r)).isEqualTo(200);
        return JsonPath.read(body(r), "$.items[*].subjectCode");
    }

    private long version(UUID workspaceId) {
        return jdbc.queryForObject("SELECT version FROM academic_class WHERE id = ?", Long.class, workspaceId);
    }

    private record Op(String name, HttpMethod method, String path, String body) {}

    /** Every state-changing or roster-reading endpoint that needs "manage" rights on the class. */
    private List<Op> manageOps(UUID workspaceId) {
        String base = "/api/v1/workspaces/" + workspaceId;
        return List.of(
            new Op("roster", HttpMethod.GET, base + "/enrollments", null),
            new Op("enroll", HttpMethod.POST, base + "/enrollments", "{\"studentNumber\":\"S-3\"}"),
            new Op("unenroll", HttpMethod.DELETE, base + "/enrollments/" + s2, null),
            new Op("update", HttpMethod.PATCH, base, "{\"version\":" + version(workspaceId) + ",\"section\":\"HACKED\"}"),
            new Op("archive", HttpMethod.POST, base + "/archive", null),
            new Op("coordinators-list", HttpMethod.GET, base + "/coordinators", null),
            new Op("coordinators-add", HttpMethod.POST, base + "/coordinators", "{\"userId\":\"" + i3 + "\"}"),
            new Op("coordinators-capabilities", HttpMethod.PATCH, base + "/coordinators/" + i3, "{\"capabilities\":[\"MANAGE_ROSTER\"]}"),
            new Op("coordinators-remove", HttpMethod.DELETE, base + "/coordinators/" + i3, null),
            new Op("owner-transfer", HttpMethod.PUT, base + "/owner", "{\"userId\":\"" + i3 + "\"}"));
    }

    // ---- visibility ----

    @Test
    void classListsAreScopedToWhatEachRoleMaySee() throws Exception {
        assertThat(listedCodes(admin)).containsExactlyInAnyOrder("CS101", "CS102");
        assertThat(listedCodes(i1)).containsExactly("CS101");
        assertThat(listedCodes(i2)).containsExactly("CS102");
        assertThat(listedCodes(i3)).isEmpty();
        assertThat(listedCodes(s1)).containsExactly("CS101");
        assertThat(listedCodes(s2)).containsExactly("CS102");
        assertThat(listedCodes(s3)).isEmpty();
        assertThat(status(call(null, HttpMethod.GET, "/api/v1/workspaces", null))).isEqualTo(401);
    }

    @Test
    void foreignClassesAreIndistinguishableFromMissingOnes() throws Exception {
        String missing = "/api/v1/workspaces/" + UUID.randomUUID();
        String detailOfMissing = JsonPath.read(body(call(admin, HttpMethod.GET, missing, null)), "$.detail");
        for (UUID outsider : List.of(i1, i3, s1, s3)) {
            MvcResult foreign = call(outsider, HttpMethod.GET, "/api/v1/workspaces/" + c2, null);
            assertThat(status(foreign)).as("outsider reading C2").isEqualTo(404);
            assertThat((String) JsonPath.read(body(foreign), "$.detail")).isEqualTo(detailOfMissing);
            assertThat(status(call(outsider, HttpMethod.GET, missing, null))).isEqualTo(404);
        }
        assertThat(status(call(i2, HttpMethod.GET, "/api/v1/workspaces/" + c2, null))).isEqualTo(200);
        assertThat(status(call(s2, HttpMethod.GET, "/api/v1/workspaces/" + c2, null))).isEqualTo(200);
        assertThat(status(call(admin, HttpMethod.GET, "/api/v1/workspaces/" + c2, null))).isEqualTo(200);
    }

    // ---- the negative matrix ----

    @Test
    void actorsOutsideAClassCannotReadOrChangeAnythingInIt() throws Exception {
        long versionBefore = version(c2);
        for (UUID outsider : List.of(i1, i3, s1, s3)) {
            for (Op op : manageOps(c2)) {
                assertThat(status(call(outsider, op.method(), op.path(), op.body())))
                    .as("%s on foreign class as %s", op.name(), outsider).isEqualTo(404);
            }
        }
        for (Op op : manageOps(c2)) {
            assertThat(status(call(null, op.method(), op.path(), op.body()))).as("anonymous " + op.name()).isEqualTo(401);
        }
        // Nothing happened.
        assertThat(version(c2)).isEqualTo(versionBefore);
        assertThat(jdbc.queryForObject("SELECT status FROM academic_class WHERE id = ?", String.class, c2)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT section FROM academic_class WHERE id = ?", String.class, c2)).isEqualTo("B");
        assertThat(jdbc.queryForList("SELECT user_id FROM class_instructor WHERE class_id = ?", UUID.class, c2)).containsExactly(i2);
        assertThat(jdbc.queryForList("SELECT student_id FROM enrollment WHERE class_id = ? AND status = 'ENROLLED'", UUID.class, c2))
            .containsExactly(s2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE resource_id = ? AND action LIKE 'CLASS_%'",
            Integer.class, c2.toString())).isZero();
    }

    @Test
    void enrolledStudentsCanViewTheirClassButNeverManageItOrSeeTheRoster() throws Exception {
        long versionBefore = version(c1);
        ClassView view = tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build()
            .readValue(body(call(s1, HttpMethod.GET, "/api/v1/workspaces/" + c1, null)), ClassView.class);
        assertThat(view.subjectCode()).isEqualTo("CS101");

        for (Op op : manageOps(c1)) {
            assertThat(status(call(s1, op.method(), op.path(), op.body()))).as("student " + op.name()).isEqualTo(403);
        }
        assertThat(status(call(s1, HttpMethod.POST, "/api/v1/workspaces/" + c1 + "/unarchive", null))).isEqualTo(403);
        assertThat(status(call(s1, HttpMethod.POST, "/api/v1/workspaces",
            "{\"academicYear\":\"y\",\"semester\":\"s\",\"subjectCode\":\"c\",\"subjectName\":\"n\",\"section\":\"x\"}")))
            .isEqualTo(403);
        assertThat(version(c1)).isEqualTo(versionBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM enrollment WHERE class_id = ?", Integer.class, c1)).isEqualTo(1);
    }

    @Test
    void onlyAdminsMayUnarchive_andNoOneButStaffOrAdminsCanCreate() throws Exception {
        for (UUID actor : List.of(i1, i2, i3, s1, s3)) {
            assertThat(status(call(actor, HttpMethod.POST, "/api/v1/workspaces/" + c1 + "/unarchive", null)))
                .as("unarchive as %s", actor).isEqualTo(403);
        }
    }

    // ---- create / update ----

    @Test
    void creatingClassesFollowsRoleRulesAndValidation() throws Exception {
        String body = "{\"academicYear\":\"2025-2026\",\"semester\":\"2nd\",\"subjectCode\":\"CS201\","
            + "\"subjectName\":\"Algorithms\",\"section\":\"A\",\"teamFormation\":\"PARTICIPANT_FORMED\","
            + "\"teamMinSize\":2,\"teamMaxSize\":4}";

        MvcResult created = call(i1, HttpMethod.POST, "/api/v1/workspaces", body);
        assertThat(status(created)).isEqualTo(201);
        assertThat((List<String>) JsonPath.read(body(created), "$.coordinators[*].id")).containsExactly(i1.toString());
        assertThat(listedCodes(i1)).contains("CS201");
        assertThat(listedCodes(i2)).doesNotContain("CS201");

        // same identity again -> conflict
        assertThat(status(call(i1, HttpMethod.POST, "/api/v1/workspaces", body))).isEqualTo(409);

        String adminBody = body.replace("CS201", "CS202");
        assertThat(status(call(admin, HttpMethod.POST, "/api/v1/workspaces", adminBody))).as("admin w/o owner").isEqualTo(400);
        assertThat(status(call(admin, HttpMethod.POST, "/api/v1/workspaces",
            adminBody.replace("}", ",\"coordinatorIds\":[\"" + i2 + "\"]}")))).as("coordinatorIds do not replace ownerId").isEqualTo(400);
        MvcResult byAdmin = call(admin, HttpMethod.POST, "/api/v1/workspaces",
            adminBody.replace("}", ",\"ownerId\":\"" + i2 + "\",\"coordinatorIds\":[\"" + i3 + "\"]}"));
        assertThat(status(byAdmin)).isEqualTo(201);
        assertThat((String) JsonPath.read(body(byAdmin), "$.ownerId")).isEqualTo(i2.toString());
        assertThat((List<String>) JsonPath.read(body(byAdmin), "$.coordinators[*].id")).containsExactlyInAnyOrder(i2.toString(), i3.toString());
        assertThat(listedCodes(i2)).contains("CS202");
        assertThat(listedCodes(admin)).contains("CS202");

        String studentAsInstructor = body.replace("CS201", "CS203").replace("}", ",\"ownerId\":\"" + s1 + "\"}");
        assertThat(status(call(admin, HttpMethod.POST, "/api/v1/workspaces", studentAsInstructor))).isEqualTo(400);
        assertThat(status(call(admin, HttpMethod.POST, "/api/v1/workspaces",
            body.replace("CS201", "CS204").replace("}", ",\"ownerId\":\"" + goneInstructor + "\"}")))).isEqualTo(400);

        assertThat(status(call(i1, HttpMethod.POST, "/api/v1/workspaces", body.replace("CS201", "CS205").replace("PARTICIPANT_FORMED", "ANARCHY")))).isEqualTo(400);
        assertThat(status(call(i1, HttpMethod.POST, "/api/v1/workspaces", body.replace("CS201", "CS206").replace("\"teamMaxSize\":4", "\"teamMaxSize\":1")))).isEqualTo(400);
        assertThat(status(call(i1, HttpMethod.POST, "/api/v1/workspaces", body.replace("\"subjectCode\":\"CS201\",", "")))).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'CLASS_CREATED'", Integer.class)).isEqualTo(2);
    }

    @Test
    void updatesUseOptimisticLockingAndStayInsideTheUniqueIdentity() throws Exception {
        String path = "/api/v1/workspaces/" + c1;
        long v = version(c1);
        assertThat(status(call(i1, HttpMethod.PATCH, path, "{\"section\":\"Z\"}"))).as("no version").isEqualTo(400);
        assertThat(status(call(i1, HttpMethod.PATCH, path, "{\"version\":" + (v + 5) + ",\"section\":\"Z\"}"))).isEqualTo(409);

        MvcResult ok = call(i1, HttpMethod.PATCH, path, "{\"version\":" + v + ",\"section\":\"Z\",\"teamMaxSize\":6}");
        assertThat(status(ok)).isEqualTo(200);
        assertThat((String) JsonPath.read(body(ok), "$.section")).isEqualTo("Z");
        assertThat((Integer) JsonPath.read(body(ok), "$.teamMaxSize")).isEqualTo(6);
        assertThat(version(c1)).isEqualTo(v + 1);

        // C2 is "CS102"/"B"; moving C1 onto the same identity is a conflict, not an overwrite.
        assertThat(status(call(admin, HttpMethod.PATCH, path,
            "{\"version\":" + version(c1) + ",\"subjectCode\":\"CS102\",\"section\":\"B\"}"))).isEqualTo(409);
        assertThat(status(call(i1, HttpMethod.PATCH, path, "{\"version\":" + version(c1) + ",\"teamMinSize\":9,\"teamMaxSize\":2}"))).isEqualTo(400);
    }

    // ---- enrollment ----

    @Test
    void enrollmentLifecycle() throws Exception {
        String roster = "/api/v1/workspaces/" + c1 + "/enrollments";

        assertThat(status(call(i1, HttpMethod.POST, roster, "{\"studentNumber\":\"S-3\",\"lastName\":\"s3\",\"program\":\"BSCS\",\"section\":\"1A\"}"))).isEqualTo(201);
        assertThat(listedCodes(s3)).containsExactly("CS101");
        MvcResult rosterResult = call(i1, HttpMethod.GET, roster, null);
        assertThat((List<String>) JsonPath.read(body(rosterResult), "$[*].studentNumber")).containsExactlyInAnyOrder("S-1", "S-3");
        assertThat((List<String>) JsonPath.read(body(rosterResult), "$[?(@.studentNumber=='S-3')].program"))
            .containsExactly("BSCS");

        assertThat(status(call(i1, HttpMethod.POST, roster, "{\"studentNumber\":\"S-3\",\"lastName\":\"s3\"}"))).as("duplicate").isEqualTo(409);
        assertThat(status(call(i1, HttpMethod.POST, roster, "{\"participantId\":\"" + s2 + "\",\"studentNumber\":\"S-2\"}"))).as("both").isEqualTo(400);
        assertThat(status(call(i1, HttpMethod.POST, roster, "{}"))).as("neither").isEqualTo(400);
        assertThat(status(call(i1, HttpMethod.POST, roster, "{\"studentNumber\":\"NOPE\",\"lastName\":\"x\"}"))).isEqualTo(404);
        assertThat(status(call(i1, HttpMethod.POST, roster, "{\"participantId\":\"" + i2 + "\"}"))).as("instructor is not a student").isEqualTo(404);
        assertThat(status(call(i1, HttpMethod.POST, roster, "{\"studentNumber\":\"S-9\",\"lastName\":\"gone-s\"}"))).as("deactivated student").isEqualTo(404);

        assertThat(status(call(i1, HttpMethod.DELETE, roster + "/" + s3, null))).isEqualTo(204);
        assertThat(status(call(s3, HttpMethod.GET, "/api/v1/workspaces/" + c1, null))).as("removed student loses access").isEqualTo(404);
        assertThat(listedCodes(s3)).isEmpty();
        assertThat((List<String>) JsonPath.read(body(call(i1, HttpMethod.GET, roster, null)), "$[*].studentNumber")).containsExactly("S-1");
        assertThat((List<String>) JsonPath.read(body(call(i1, HttpMethod.GET, roster + "?includeRemoved=true", null)), "$[?(@.status=='REMOVED')].studentNumber"))
            .containsExactly("S-3");

        assertThat(status(call(i1, HttpMethod.POST, roster, "{\"participantId\":\"" + s3 + "\"}"))).as("re-enroll").isEqualTo(201);
        assertThat(listedCodes(s3)).containsExactly("CS101");
        assertThat(status(call(i1, HttpMethod.DELETE, roster + "/" + s3, null))).isEqualTo(204);
        assertThat(status(call(i1, HttpMethod.DELETE, roster + "/" + s3, null))).as("already removed").isEqualTo(404);
        assertThat(status(call(i1, HttpMethod.DELETE, roster + "/" + s2, null))).as("never enrolled here").isEqualTo(404);
        // s2 still belongs to C2 only
        assertThat(listedCodes(s2)).containsExactly("CS102");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action IN ('STUDENT_ENROLLED','STUDENT_REMOVED') AND resource_id = ?",
            Integer.class, c1.toString())).isEqualTo(4);
    }

    // ---- archive ----

    @Test
    void archivedClassesAreReadOnlyUntilAnAdminUnarchivesThem() throws Exception {
        String base = "/api/v1/workspaces/" + c1;
        MvcResult archived = call(i1, HttpMethod.POST, base + "/archive", null);
        assertThat(status(archived)).isEqualTo(200);
        assertThat((String) JsonPath.read(body(archived), "$.status")).isEqualTo("ARCHIVED");
        assertThat(status(call(i1, HttpMethod.POST, base + "/archive", null))).as("idempotent").isEqualTo(200);

        for (Op op : manageOps(c1)) {
            // reads, the idempotent archive and the ownership transfer (owner recovery) stay possible while archived
            if (List.of("roster", "archive", "coordinators-list", "owner-transfer").contains(op.name())) continue;
            assertThat(status(call(i1, op.method(), op.path(), op.body()))).as("archived: " + op.name()).isEqualTo(409);
        }
        assertThat(status(call(i1, HttpMethod.GET, base + "/enrollments", null))).isEqualTo(200);
        assertThat(status(call(s1, HttpMethod.GET, base, null))).as("students still see archived classes").isEqualTo(200);
        MvcResult onlyArchived = call(s1, HttpMethod.GET, "/api/v1/workspaces?status=ARCHIVED", null);
        assertThat((List<String>) JsonPath.read(body(onlyArchived), "$.items[*].subjectCode")).containsExactly("CS101");
        assertThat((List<String>) JsonPath.read(body(call(s1, HttpMethod.GET, "/api/v1/workspaces?status=ACTIVE", null)), "$.items[*].subjectCode")).isEmpty();

        assertThat(status(call(i1, HttpMethod.POST, base + "/unarchive", null))).isEqualTo(403);
        assertThat(status(call(admin, HttpMethod.POST, base + "/unarchive", null))).isEqualTo(200);
        assertThat(status(call(i1, HttpMethod.PATCH, base, "{\"version\":" + version(c1) + ",\"section\":\"Q\"}"))).isEqualTo(200);
    }

    // ---- search ----

    @Test
    void searchFiltersWithinTheCallersScopeOnly() throws Exception {
        assertThat((List<String>) JsonPath.read(body(call(admin, HttpMethod.GET, "/api/v1/workspaces?q=cs102", null)), "$.items[*].subjectCode"))
            .containsExactly("CS102");
        // i1 searching for the other class's code finds nothing: scoping happens in SQL.
        assertThat((Integer) JsonPath.read(body(call(i1, HttpMethod.GET, "/api/v1/workspaces?q=cs102", null)), "$.total")).isZero();
        assertThat((Integer) JsonPath.read(body(call(admin, HttpMethod.GET, "/api/v1/workspaces?q=%25", null)), "$.total")).isZero();
        assertThat(status(call(admin, HttpMethod.GET, "/api/v1/workspaces?status=WIZARD", null))).isEqualTo(400);
        assertThat((Integer) JsonPath.read(body(call(admin, HttpMethod.GET, "/api/v1/workspaces?size=1", null)), "$.total")).isEqualTo(2);
        assertThat((List<String>) JsonPath.read(body(call(admin, HttpMethod.GET, "/api/v1/workspaces?size=1", null)), "$.items[*].subjectCode")).hasSize(1);
    }
}
