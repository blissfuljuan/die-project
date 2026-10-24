package com.blissfuljuan.dieapi.academic;

import com.blissfuljuan.dieapi.PostgresIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Security review findings H1 and H2: an instructor must not be able to claim another class's
 * never-activated students (and then re-issue their temporary credentials), and must not learn anything
 * about accounts outside the classes they teach through roster import or manual enrollment.
 */
class CrossClassIsolationIntegrationTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct horse battery staple";
    private static final String HEADER = "Student ID,Last Name,First Name,Middle Name,Email,Program,Section\n";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;

    UUID admin, instrX, instrY, pendingX, pendingByAdmin, activeStudent, goneStudent, staffWithNumber;
    UUID classX, classY;
    final Map<UUID, Cookie> sessions = new HashMap<>();

    @BeforeEach
    void seed() {
        sessions.clear();
        admin = user("admin@x.edu", "ADMIN", "ACTIVE", "Admin", "Root", null, null);
        instrX = user("x@x.edu", "COORDINATOR", "ACTIVE", "Xavier", "Xia", null, null);
        instrY = user("y@x.edu", "COORDINATOR", "ACTIVE", "Yates", "Yan", null, null);
        pendingX = user(null, "PARTICIPANT", "PENDING_ACTIVATION", "Pend", "Pat", "P-X", instrX);
        pendingByAdmin = user(null, "PARTICIPANT", "PENDING_ACTIVATION", "Adm", "Ann", "P-A", admin);
        activeStudent = user("active@x.edu", "PARTICIPANT", "ACTIVE", "Active", "Alex", "A-1", null);
        goneStudent = user("gone@x.edu", "PARTICIPANT", "DEACTIVATED", "Gone", "Guy", "G-1", null);
        staffWithNumber = user("t@x.edu", "COORDINATOR", "ACTIVE", "Teach", "Tom", "T-1", null);
        classX = clazz("CSX", instrX);
        classY = clazz("CSY", instrY);
        enroll(classX, pendingX);
        enroll(classX, activeStudent);
        for (UUID s : List.of(pendingX, pendingByAdmin)) {
            jdbc.update("INSERT INTO temp_credential (user_id, secret_hash, expires_at) VALUES (?, ?, now() + interval '14 days')",
                s, encoder.encode("Temp-" + s));
        }
    }

    // ---- fixtures / helpers ----

    private UUID user(String email, String role, String status, String last, String first, String number, UUID createdBy) {
        boolean active = "ACTIVE".equals(status) || "DEACTIVATED".equals(status);
        return jdbc.queryForObject("""
            INSERT INTO app_user (role, status, student_number, last_name, first_name, email, email_verified_at, password_hash, created_by)
            VALUES (?, ?, ?, ?, ?, ?, CASE WHEN ? THEN now() END, CASE WHEN ? THEN ? END, ?) RETURNING id
            """, UUID.class, role, status, number, last, first, email, active, active, encoder.encode(PASSWORD), createdBy);
    }

    private UUID clazz(String code, UUID instructor) {
        UUID id = jdbc.queryForObject("""
            WITH c AS (INSERT INTO academic_class (academic_year, semester, subject_code, subject_name, section, owner_id)
            VALUES ('2025-2026', '1st', ?, 'Subject', 'A', ?) RETURNING id)
            INSERT INTO class_instructor (class_id, user_id) SELECT id, ? FROM c RETURNING class_id
            """, UUID.class, code, instructor, instructor);
        return id;
    }

    private void enroll(UUID workspaceId, UUID student) {
        jdbc.update("INSERT INTO enrollment (class_id, student_id) VALUES (?, ?)", workspaceId, student);
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

    private MvcResult call(UUID actor, HttpMethod method, String path, String json) throws Exception {
        MockHttpServletRequestBuilder req = request(method, path);
        if (method != HttpMethod.GET) req.with(csrf());
        if (actor != null) req.cookie(as(actor));
        if (json != null) req.contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(req).andReturn();
    }

    private MvcResult upload(UUID actor, UUID workspaceId, String csv) throws Exception {
        return mvc.perform(multipart("/api/v1/workspaces/" + workspaceId + "/roster-imports")
            .file(new MockMultipartFile("file", "roster.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .with(csrf()).cookie(as(actor))).andReturn();
    }

    private static int status(MvcResult r) { return r.getResponse().getStatus(); }
    private static String body(MvcResult r) throws Exception { return r.getResponse().getContentAsString(); }

    private String detail(MvcResult r) throws Exception { return JsonPath.read(body(r), "$.detail"); }

    private List<Object> rowField(UUID importId, UUID actor, int row, String field) throws Exception {
        MvcResult r = call(actor, HttpMethod.GET, "/api/v1/roster-imports/" + importId, null);
        return JsonPath.read(body(r), "$.rows[?(@.rowNumber==" + row + ")]." + field);
    }

    // ================= H1: no claiming of other classes' pending accounts =================

    @Test
    void anotherInstructorCannotEnrollImportOrReissueAPendingStudentOfAnotherClass() throws Exception {
        String enrollY = "/api/v1/workspaces/" + classY + "/enrollments";
        String missing = detail(call(instrY, HttpMethod.POST, enrollY, "{\"studentNumber\":\"NOPE\",\"lastName\":\"Nobody\"}"));

        // Manual enrollment: by number + correct last name, and by UUID.
        MvcResult byNumber = call(instrY, HttpMethod.POST, enrollY, "{\"studentNumber\":\"P-X\",\"lastName\":\"Pend\"}");
        assertThat(status(byNumber)).isEqualTo(404);
        assertThat(detail(byNumber)).isEqualTo(missing);
        assertThat(status(call(instrY, HttpMethod.POST, enrollY, "{\"participantId\":\"" + pendingX + "\"}"))).isEqualTo(404);
        // Accounts an admin created are not claimable by coordinators either (not even by the instructor of the other class).
        assertThat(status(call(instrY, HttpMethod.POST, enrollY, "{\"studentNumber\":\"P-A\",\"lastName\":\"Adm\"}"))).isEqualTo(404);
        assertThat(status(call(instrX, HttpMethod.POST, "/api/v1/workspaces/" + classX + "/enrollments",
            "{\"studentNumber\":\"P-A\",\"lastName\":\"Adm\"}"))).as("created by the admin, not by X").isEqualTo(404);

        // Roster import: the row becomes an unusable, skip-only conflict.
        MvcResult uploaded = upload(instrY, classY, HEADER + "P-X,Pend,Pat,,,,\nP-A,Adm,Ann,,,,\n");
        assertThat(status(uploaded)).isEqualTo(201);
        UUID importId = UUID.fromString(JsonPath.read(body(uploaded), "$.id"));
        for (int row : List.of(2, 3)) {
            assertThat(rowField(importId, instrY, row, "action")).containsExactly("CONFLICT");
            assertThat(rowField(importId, instrY, row, "conflictKind")).containsExactly("ID_UNAVAILABLE");
        }
        assertThat(status(call(instrY, HttpMethod.PUT, "/api/v1/roster-imports/" + importId + "/resolutions",
            "{\"resolutions\":[{\"rowNumber\":2,\"resolution\":\"USE_EXISTING\"}]}"))).as("cannot be forced").isEqualTo(400);
        assertThat(status(call(instrY, HttpMethod.POST, "/api/v1/roster-imports/" + importId + "/commit", null)))
            .as("unresolved conflicts block the commit").isEqualTo(409);
        call(instrY, HttpMethod.PUT, "/api/v1/roster-imports/" + importId + "/resolutions",
            "{\"resolutions\":[{\"rowNumber\":2,\"resolution\":\"SKIP\"},{\"rowNumber\":3,\"resolution\":\"SKIP\"}]}");
        MvcResult committed = call(instrY, HttpMethod.POST, "/api/v1/roster-imports/" + importId + "/commit", null);
        assertThat(status(committed)).isEqualTo(200);
        assertThat((Integer) JsonPath.read(body(committed), "$.enrolledExisting")).isZero();

        // Re-issue needs the student to be enrolled in a class the caller teaches.
        for (UUID target : List.of(pendingX, pendingByAdmin)) {
            assertThat(status(call(instrY, HttpMethod.POST, "/api/v1/workspaces/" + classY + "/enrollments/" + target + "/temporary-credential", null)))
                .as("Y re-issuing for %s", target).isEqualTo(404);
            assertThat(status(call(instrY, HttpMethod.POST, "/api/v1/workspaces/" + classX + "/enrollments/" + target + "/temporary-credential", null)))
                .as("Y using X's class for %s", target).isEqualTo(404);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM enrollment WHERE class_id = ?", Integer.class, classY)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM temp_credential WHERE user_id = ? AND invalidated_at IS NULL", Integer.class, pendingX))
            .as("the legitimate credential is untouched").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'TEMP_CREDENTIAL_REISSUED'", Integer.class)).isZero();

        // The class that really owns the account can still help a student who lost the sheet.
        assertThat(status(call(instrX, HttpMethod.POST, "/api/v1/workspaces/" + classX + "/enrollments/" + pendingX + "/temporary-credential", null))).isEqualTo(200);
    }

    @Test
    void anAdminCanPlaceAnAdminCreatedStudentAndOnlyThenTheClassStaffCanHelpThem() throws Exception {
        String enrollX = "/api/v1/workspaces/" + classX + "/enrollments";
        assertThat(status(call(admin, HttpMethod.POST, enrollX, "{\"studentNumber\":\"P-A\"}"))).as("admins need no last name").isEqualTo(201);
        assertThat(status(call(instrX, HttpMethod.POST, enrollX + "/" + pendingByAdmin + "/temporary-credential", null))).isEqualTo(200);
        assertThat(status(call(instrY, HttpMethod.POST, "/api/v1/workspaces/" + classY + "/enrollments/" + pendingByAdmin + "/temporary-credential", null))).isEqualTo(404);
    }

    // ================= H2: no disclosure of other accounts =================

    @Test
    void rosterImportAnswersTheSameForEveryIdTheCallerCannotUseAndNeverNamesAnyone() throws Exception {
        String csv = HEADER
            + "A-1,Wrong,Name,,,,\n"          // 2: active account of another class, different name
            + "G-1,Gone,Guy,,,,\n"            // 3: deactivated account
            + "T-1,Any,One,,,,\n"             // 4: id belongs to a staff account
            + "P-X,Not,Pend,,,,\n"            // 5: pending account of another class, different name
            + "N-1,Brand,New,,,,\n"           // 6: nobody has this id
            + "A-1,Active,Alex,,,,\n";        // 7: right id AND right name of an active student (accepted residual: MATCH)
        MvcResult uploaded = upload(instrY, classY, csv);
        assertThat(status(uploaded)).isEqualTo(201);
        UUID importId = UUID.fromString(JsonPath.read(body(uploaded), "$.id"));
        String detail = body(call(instrY, HttpMethod.GET, "/api/v1/roster-imports/" + importId, null));

        List<Object> messages = new java.util.ArrayList<>();
        for (int row : List.of(2, 3, 4, 5)) {
            assertThat(rowField(importId, instrY, row, "conflictKind")).as("row " + row).containsExactly("ID_UNAVAILABLE");
            assertThat(((List<?>) rowField(importId, instrY, row, "allowedResolutions").get(0)).stream().map(Object::toString).toList())
                .containsExactly("SKIP");
            messages.add(rowField(importId, instrY, row, "message").get(0));
        }
        assertThat(messages).as("every unusable id gets the identical message").containsOnly(messages.get(0));
        assertThat(rowField(importId, instrY, 6, "action")).containsExactly("CREATE");
        assertThat(rowField(importId, instrY, 7, "conflictKind")).containsExactly("ID_REPEATED_WITH_DIFFERENT_NAME"); // same id as row 2

        // Nothing the caller did not type leaks into the response: other accounts' names, emails or ids.
        // (Teach/Tom = the staff account, Pat = the pending student's first name, Xavier = the other instructor.)
        assertThat(detail).doesNotContain("Teach").doesNotContain("Tom").doesNotContain("Pat").doesNotContain("Xavier")
            .doesNotContain("active@x.edu").doesNotContain("gone@x.edu").doesNotContain("matchedStudentId")
            .doesNotContain(activeStudent.toString()).doesNotContain(goneStudent.toString())
            .doesNotContain(staffWithNumber.toString()).doesNotContain(pendingX.toString());

        // The one that matches by id and exact name is reported plainly, without anything from the account.
        MvcResult exact = upload(instrY, classY, HEADER + "A-1,Active,Alex,,,,\n");
        UUID exactImport = UUID.fromString(JsonPath.read(body(exact), "$.id"));
        assertThat(rowField(exactImport, instrY, 2, "action")).containsExactly("MATCH");
        assertThat(body(call(instrY, HttpMethod.GET, "/api/v1/roster-imports/" + exactImport, null))).doesNotContain("active@x.edu");
    }

    @Test
    void onlyAStudentTheCallerAlreadyTeachesCanBeResolvedWithUseExistingAndTheNameStaysHidden() throws Exception {
        MvcResult uploaded = upload(instrX, classX, HEADER + "A-1,Wrong,Name,,,,\n");
        UUID importId = UUID.fromString(JsonPath.read(body(uploaded), "$.id"));
        assertThat(rowField(importId, instrX, 2, "conflictKind")).containsExactly("ID_NAME_MISMATCH");
        assertThat(rowField(importId, instrX, 2, "allowedResolutions").get(0).toString()).contains("USE_EXISTING");
        assertThat(rowField(importId, instrX, 2, "message").get(0).toString()).doesNotContain("Active").doesNotContain("Alex");
        assertThat(body(call(instrX, HttpMethod.GET, "/api/v1/roster-imports/" + importId, null))).doesNotContain(activeStudent.toString());
    }

    @Test
    void manualEnrollmentByNumberNeedsTheLastNameAndAllRefusalsLookAlike() throws Exception {
        String enrollY = "/api/v1/workspaces/" + classY + "/enrollments";
        assertThat(status(call(instrY, HttpMethod.POST, enrollY, "{\"studentNumber\":\"A-1\"}"))).as("last name required").isEqualTo(400);

        String unknown = detail(call(instrY, HttpMethod.POST, enrollY, "{\"studentNumber\":\"ZZZ\",\"lastName\":\"Zed\"}"));
        for (String body : List.of(
                "{\"studentNumber\":\"A-1\",\"lastName\":\"Wrong\"}",        // active student, wrong last name
                "{\"studentNumber\":\"G-1\",\"lastName\":\"Gone\"}",         // deactivated
                "{\"studentNumber\":\"T-1\",\"lastName\":\"Teach\"}",        // staff account
                "{\"studentNumber\":\"P-X\",\"lastName\":\"Pend\"}")) {      // pending account of another class
            MvcResult r = call(instrY, HttpMethod.POST, enrollY, body);
            assertThat(status(r)).as(body).isEqualTo(404);
            assertThat(detail(r)).as(body).isEqualTo(unknown);
        }
        assertThat(status(call(instrY, HttpMethod.POST, enrollY, "{\"studentNumber\":\"a-1\",\"lastName\":\"ACTIVE\"}")))
            .as("case-insensitive match of an active student is allowed").isEqualTo(201);
    }

    @Test
    void emailsInARosterAreNeverAppliedAndCannotBeUsedToProbeRegisteredAddresses() throws Exception {
        MvcResult uploaded = upload(instrY, classY, HEADER + "N-9,New,Nina,,active@x.edu,,\nN-10,New,Nico,,unregistered@x.edu,,\n");
        UUID importId = UUID.fromString(JsonPath.read(body(uploaded), "$.id"));
        assertThat(rowField(importId, instrY, 2, "action")).containsExactly("CREATE");
        assertThat(rowField(importId, instrY, 3, "action")).containsExactly("CREATE");
        assertThat(rowField(importId, instrY, 2, "message")).isEqualTo(rowField(importId, instrY, 3, "message"));
        assertThat(status(call(instrY, HttpMethod.POST, "/api/v1/roster-imports/" + importId + "/commit", null))).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE student_number IN ('N-9', 'N-10') AND email IS NOT NULL", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT student_number FROM app_user WHERE email = 'active@x.edu'", String.class)).isEqualTo("A-1");
    }

    // ================= M5: student numbers are unique ignoring case =================

    @Test
    void studentNumbersCollideIgnoringCaseInTheApiTheDatabaseAndTheImporter() throws Exception {
        String create = "{\"role\":\"PARTICIPANT\",\"studentNumber\":\"%s\",\"lastName\":\"L\",\"firstName\":\"F\"}";
        assertThat(status(call(admin, HttpMethod.POST, "/api/v1/users", create.formatted("ab-1")))).isEqualTo(201);
        assertThat(status(call(admin, HttpMethod.POST, "/api/v1/users", create.formatted("AB-1")))).as("same id, other case").isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE lower(student_number) = 'ab-1'", Integer.class)).isEqualTo(1);

        // The importer treats the differently-cased id as the same student instead of creating a second account.
        jdbc.update("UPDATE app_user SET status = 'ACTIVE', email = 'ab@x.edu', email_verified_at = now(), password_hash = ? WHERE lower(student_number) = 'ab-1'",
            encoder.encode(PASSWORD));
        MvcResult uploaded = upload(instrY, classY, HEADER + "AB-1,L,F,,,,\n");
        UUID importId = UUID.fromString(JsonPath.read(body(uploaded), "$.id"));
        assertThat(rowField(importId, instrY, 2, "action")).containsExactly("MATCH");
    }

    @Test
    void twoClassesImportingTheSameNewStudentNeverEndInAServerError() throws Exception {
        UUID importX = UUID.fromString(JsonPath.read(body(upload(instrX, classX, HEADER + "N-77,Twin,Tia,,,,\n")), "$.id"));
        UUID importY = UUID.fromString(JsonPath.read(body(upload(instrY, classY, HEADER + "N-77,Twin,Tia,,,,\n")), "$.id"));
        assertThat(status(call(instrX, HttpMethod.POST, "/api/v1/roster-imports/" + importX + "/commit", null))).isEqualTo(200);

        MvcResult second = call(instrY, HttpMethod.POST, "/api/v1/roster-imports/" + importY + "/commit", null);
        assertThat(status(second)).as("stale preview is refused cleanly").isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE lower(student_number) = 'n-77'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM enrollment WHERE class_id = ?", Integer.class, classY)).isZero();

        // A fresh preview shows Y the truth: that id cannot be claimed.
        UUID again = UUID.fromString(JsonPath.read(body(upload(instrY, classY, HEADER + "N-77,Twin,Tia,,,,\n")), "$.id"));
        assertThat(rowField(again, instrY, 2, "conflictKind")).containsExactly("ID_UNAVAILABLE");
    }
}
