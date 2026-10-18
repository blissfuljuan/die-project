package com.blissfuljuan.dieapi.roster;

import com.blissfuljuan.dieapi.PostgresIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

class RosterImportIntegrationTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct horse battery staple";
    private static final String HEADER = "Student ID,Last Name,First Name,Middle Name,Email,Program,Section\n";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;

    UUID admin, i1, i2, i3, emailOwner, ana, ben, carl, dina, gone, outsiderStudent;
    UUID c1, c2;
    final Map<UUID, Cookie> sessions = new HashMap<>();

    @BeforeEach
    void seed() {
        sessions.clear();
        admin = user("admin@x.edu", "ADMIN", "ACTIVE", "Admin", "Root", null);
        i1 = user("i1@x.edu", "COORDINATOR", "ACTIVE", "One", "Ian", null);
        i2 = user("i2@x.edu", "COORDINATOR", "ACTIVE", "Two", "Ivy", null);
        i3 = user("i3@x.edu", "COORDINATOR", "ACTIVE", "Three", "Ike", null);
        emailOwner = user("taken@x.edu", "COORDINATOR", "ACTIVE", "Owner", "Email", null);
        ana = user("ana@x.edu", "PARTICIPANT", "ACTIVE", "Cruz", "Ana", "2024-0001");
        ben = user("ben@x.edu", "PARTICIPANT", "ACTIVE", "Diaz", "Ben", "2024-0002");
        carl = user("carl@x.edu", "PARTICIPANT", "ACTIVE", "Eze", "Carl", "2024-0003");
        dina = user("dina@x.edu", "PARTICIPANT", "ACTIVE", "Fox", "Dina", "2024-0004");
        gone = user("gone@x.edu", "PARTICIPANT", "DEACTIVATED", "Gone", "Student", "2024-0009");
        outsiderStudent = user("out@x.edu", "PARTICIPANT", "ACTIVE", "Out", "Side", "S-77");
        c1 = clazz("CS101", "A", i1);
        c2 = clazz("CS102", "B", i2);
        enroll(c1, ben, "ENROLLED");
        enroll(c1, carl, "REMOVED");
        enroll(c1, dina, "REMOVED");   // dina is "known" to i1, so a name mismatch can be resolved with USE_EXISTING
    }

    // ---- fixtures / helpers ----

    private UUID user(String email, String role, String status, String last, String first, String studentNumber) {
        return jdbc.queryForObject("""
            INSERT INTO app_user (role, status, student_number, last_name, first_name, email, email_verified_at, password_hash)
            VALUES (?, ?, ?, ?, ?, ?, now(), ?) RETURNING id
            """, UUID.class, role, status, studentNumber, last, first, email, encoder.encode(PASSWORD));
    }

    private UUID clazz(String code, String section, UUID instructor) {
        UUID id = jdbc.queryForObject("""
            INSERT INTO academic_class (academic_year, semester, subject_code, subject_name, section)
            VALUES ('2025-2026', '1st', ?, 'Subject', ?) RETURNING id
            """, UUID.class, code, section);
        jdbc.update("INSERT INTO class_instructor (class_id, user_id) VALUES (?, ?)", id, instructor);
        return id;
    }

    private void enroll(UUID workspaceId, UUID student, String status) {
        jdbc.update("INSERT INTO enrollment (class_id, student_id, status, removed_at) VALUES (?, ?, ?, CASE WHEN ? = 'REMOVED' THEN now() END)",
            workspaceId, student, status, status);
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

    private MvcResult upload(UUID actor, UUID workspaceId, String fileName, byte[] bytes) throws Exception {
        var req = multipart("/api/v1/workspaces/" + workspaceId + "/roster-imports")
            .file(new MockMultipartFile("file", fileName, "application/octet-stream", bytes)).with(csrf());
        if (actor != null) req.cookie(as(actor));
        return mvc.perform(req).andReturn();
    }

    private UUID uploadCsv(UUID actor, UUID workspaceId, String csv) throws Exception {
        MvcResult r = upload(actor, workspaceId, "roster.csv", csv.getBytes(StandardCharsets.UTF_8));
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(201);
        return UUID.fromString(JsonPath.read(body(r), "$.id"));
    }

    private static int status(MvcResult r) { return r.getResponse().getStatus(); }
    private static String body(MvcResult r) throws Exception { return r.getResponse().getContentAsString(); }

    private Object field(UUID importId, UUID actor, int row, String field) throws Exception {
        MvcResult r = call(actor, HttpMethod.GET, "/api/v1/roster-imports/" + importId + "?size=500", null);
        List<Object> found = JsonPath.read(body(r), "$.rows[?(@.rowNumber==" + row + ")]." + field);
        return found.isEmpty() ? null : found.get(0);
    }

    private String resolutions(Object... pairs) {
        StringBuilder sb = new StringBuilder("{\"resolutions\":[");
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) sb.append(',');
            sb.append("{\"rowNumber\":").append(pairs[i]).append(",\"resolution\":\"").append(pairs[i + 1]).append("\"}");
        }
        return sb.append("]}").toString();
    }

    private int countUsersLike(String prefix) {
        return jdbc.queryForObject("SELECT count(*) FROM app_user WHERE student_number LIKE ?", Integer.class, prefix + "%");
    }

    private static final String BIG_CSV = HEADER
        + "2024-0100,Reyes,Mia,,mia@x.edu,BSCS,1A\n"          // 2 CREATE
        + "2024-0101,Lim,Noel,,,,\n"                           // 3 CREATE
        + "2024-0001,Cruz,Ana,,,BSIT,2B\n"                     // 4 MATCH
        + "2024-0002,Diaz,Ben,,,,\n"                           // 5 SKIP_DUPLICATE (already enrolled)
        + "2024-0003,Eze,Carl,,,,\n"                           // 6 MATCH (re-enroll removed)
        + "2024-0100,Reyes,Mia,,,,\n"                          // 7 SKIP_DUPLICATE (repeated in file)
        + "2024-0100,Other,Person,,,,\n"                       // 8 CONFLICT ID_REPEATED_WITH_DIFFERENT_NAME
        + "2024-0004,Foxx,Dina,,,,\n"                          // 9 CONFLICT ID_NAME_MISMATCH
        + "2024-0102,Ong,Pia,,taken@x.edu,,\n"                 // 10 CREATE (emails are never applied, so no email conflict)
        + "2024-0103,Poe,Quin,,mia@x.edu,,\n"                  // 11 CREATE
        + "2024-0009,Gone,Student,,,,\n"                       // 12 CONFLICT ID_UNAVAILABLE (deactivated account)
        + ",Nameless,Row,,,,\n"                                // 13 INVALID (no id)
        + "2024-0104,\"=cmd|' /C calc'!A0\",Evil,,,,\n"        // 14 INVALID (formula injection)
        + "2024-0105,Bad,Email,,not-an-email,,\n";             // 15 INVALID (email)

    // ---- the whole flow ----

    @Test
    void previewResolveCommitCreatesAccountsEnrollsStudentsAndIssuesCredentialsOnce() throws Exception {
        MvcResult uploaded = upload(i1, c1, "roster.csv", BIG_CSV.getBytes(StandardCharsets.UTF_8));
        assertThat(status(uploaded)).isEqualTo(201);
        UUID importId = UUID.fromString(JsonPath.read(body(uploaded), "$.id"));
        assertThat((String) JsonPath.read(body(uploaded), "$.status")).isEqualTo("PREVIEW");
        assertThat((Integer) JsonPath.read(body(uploaded), "$.summary.total")).isEqualTo(14);
        assertThat((Integer) JsonPath.read(body(uploaded), "$.summary.create")).isEqualTo(4);
        assertThat((Integer) JsonPath.read(body(uploaded), "$.summary.match")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(body(uploaded), "$.summary.skipDuplicate")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(body(uploaded), "$.summary.conflict")).isEqualTo(3);
        assertThat((Integer) JsonPath.read(body(uploaded), "$.summary.invalid")).isEqualTo(3);
        assertThat((Integer) JsonPath.read(body(uploaded), "$.summary.unresolvedConflicts")).isEqualTo(3);

        Map<Integer, String> expectedAction = Map.ofEntries(
            Map.entry(2, "CREATE"), Map.entry(3, "CREATE"), Map.entry(4, "MATCH"), Map.entry(5, "SKIP_DUPLICATE"),
            Map.entry(6, "MATCH"), Map.entry(7, "SKIP_DUPLICATE"), Map.entry(8, "CONFLICT"), Map.entry(9, "CONFLICT"),
            Map.entry(10, "CREATE"), Map.entry(11, "CREATE"), Map.entry(12, "CONFLICT"), Map.entry(13, "INVALID"),
            Map.entry(14, "INVALID"), Map.entry(15, "INVALID"));
        expectedAction.forEach((row, action) -> {
            try {
                assertThat(field(importId, i1, row, "action")).as("row " + row).isEqualTo(action);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        assertThat(field(importId, i1, 8, "conflictKind")).isEqualTo("ID_REPEATED_WITH_DIFFERENT_NAME");
        assertThat(field(importId, i1, 9, "conflictKind")).isEqualTo("ID_NAME_MISMATCH");
        assertThat(field(importId, i1, 12, "conflictKind")).isEqualTo("ID_UNAVAILABLE");
        assertThat(field(importId, i1, 9, "message").toString()).doesNotContain("Fox").doesNotContain("Dina");
        assertThat(field(importId, i1, 9, "allowedResolutions").toString()).contains("USE_EXISTING");

        // Nothing is written by a preview.
        assertThat(countUsersLike("2024-01")).isZero();
        assertThat(status(call(i1, HttpMethod.POST, "/api/v1/roster-imports/" + importId + "/commit", null)))
            .as("unresolved conflicts block commit").isEqualTo(409);
        assertThat(countUsersLike("2024-01")).isZero();

        // Resolutions are validated against the kind of conflict.
        String url = "/api/v1/roster-imports/" + importId + "/resolutions";
        for (String bad : List.of(resolutions(9, "CREATE_NEW"), resolutions(2, "SKIP"), resolutions(10, "USE_EXISTING"),
                resolutions(12, "USE_EXISTING"), resolutions(99, "SKIP"), "{\"resolutions\":[]}")) {
            assertThat(status(call(i1, HttpMethod.PUT, url, bad))).as(bad).isEqualTo(400);
        }
        MvcResult resolved = call(i1, HttpMethod.PUT, url, resolutions(8, "SKIP", 9, "USE_EXISTING", 12, "SKIP"));
        assertThat(status(resolved)).isEqualTo(200);
        assertThat((Integer) JsonPath.read(body(resolved), "$.summary.unresolvedConflicts")).isZero();

        MvcResult committed = call(i1, HttpMethod.POST, "/api/v1/roster-imports/" + importId + "/commit", null);
        assertThat(status(committed)).isEqualTo(200);
        assertThat(committed.getResponse().getHeader("Cache-Control")).contains("no-store");
        String commitBody = body(committed);
        assertThat((Integer) JsonPath.read(commitBody, "$.created")).isEqualTo(4);
        assertThat((Integer) JsonPath.read(commitBody, "$.enrolledExisting")).isEqualTo(3);
        assertThat((Integer) JsonPath.read(commitBody, "$.skippedDuplicates")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(commitBody, "$.skippedByResolution")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(commitBody, "$.invalid")).isEqualTo(3);
        List<String> secrets = JsonPath.read(commitBody, "$.credentials[*].temporaryCredential");
        List<String> numbers = JsonPath.read(commitBody, "$.credentials[*].studentNumber");
        assertThat(secrets).hasSize(4).doesNotHaveDuplicates().allSatisfy(s -> assertThat(s).hasSize(14));
        assertThat(numbers).containsExactlyInAnyOrder("2024-0100", "2024-0101", "2024-0102", "2024-0103");

        // New accounts: pending students, enrolled, hashed credentials, emails only where unambiguous.
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM app_user u JOIN enrollment e ON e.student_id = u.id AND e.class_id = ? AND e.status = 'ENROLLED' "
                + "WHERE u.student_number LIKE '2024-01%' AND u.role = 'PARTICIPANT' AND u.status = 'PENDING_ACTIVATION'",
            Integer.class, c1)).isEqualTo(4);
        // Emails in a roster are never applied: students supply and verify their own at activation.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE student_number LIKE '2024-01%' AND email IS NOT NULL", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE student_number LIKE '2024-01%' AND created_by = ?", Integer.class, i1)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT email FROM app_user WHERE id = ?", String.class, emailOwner)).isEqualTo("taken@x.edu");
        List<String> hashes = jdbc.queryForList("SELECT t.secret_hash FROM temp_credential t JOIN app_user u ON u.id = t.user_id WHERE u.student_number LIKE '2024-01%'", String.class);
        assertThat(hashes).hasSize(4).allSatisfy(h -> assertThat(h).startsWith("$argon2"));
        assertThat(String.join(" ", hashes)).doesNotContain(secrets.get(0));

        // Existing accounts were enrolled (not duplicated, not renamed); skipped rows changed nothing.
        assertThat(jdbc.queryForObject("SELECT program FROM enrollment WHERE class_id = ? AND student_id = ?", String.class, c1, ana)).isEqualTo("BSIT");
        assertThat(jdbc.queryForObject("SELECT status FROM enrollment WHERE class_id = ? AND student_id = ?", String.class, c1, carl)).isEqualTo("ENROLLED");
        assertThat(jdbc.queryForObject("SELECT status FROM enrollment WHERE class_id = ? AND student_id = ?", String.class, c1, dina)).isEqualTo("ENROLLED");
        assertThat(jdbc.queryForObject("SELECT last_name FROM app_user WHERE id = ?", String.class, dina)).isEqualTo("Fox");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM enrollment WHERE class_id = ? AND student_id = ?", Integer.class, c1, gone)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM enrollment WHERE class_id = ? AND student_id = ?", Integer.class, c1, ben)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE student_number IN ('2024-0104','2024-0105')", Integer.class)).isZero();

        // The issued credential really works with the activation flow of slice 3.
        MvcResult check = call(null, HttpMethod.POST, "/api/v1/auth/activation/check",
            "{\"identifier\":\"2024-0100\",\"temporaryCredential\":\"" + credentialOf(commitBody, "2024-0100") + "\"}");
        assertThat(status(check)).isEqualTo(200);
        assertThat((String) JsonPath.read(body(check), "$.firstName")).isEqualTo("Mia");

        // Re-commit is a no-op that never repeats credentials.
        MvcResult again = call(i1, HttpMethod.POST, "/api/v1/roster-imports/" + importId + "/commit", null);
        assertThat(status(again)).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(body(again), "$.alreadyCommitted")).isTrue();
        assertThat((Integer) JsonPath.read(body(again), "$.created")).isEqualTo(4);
        assertThat((List<Object>) JsonPath.read(body(again), "$.credentials")).isEmpty();
        assertThat(countUsersLike("2024-01")).isEqualTo(4);
        assertThat(status(call(i1, HttpMethod.PUT, url, resolutions(8, "SKIP")))).as("committed imports are frozen").isEqualTo(409);
        assertThat(status(call(i1, HttpMethod.DELETE, "/api/v1/roster-imports/" + importId, null))).isEqualTo(409);

        // Audit: counts and created ids, never secrets.
        String audit = jdbc.queryForObject("SELECT details::text FROM audit_log WHERE action = 'ROSTER_IMPORT_COMMITTED'", String.class);
        assertThat(audit).contains("\"created\": 4").doesNotContain(secrets.get(0));
    }

    private static String credentialOf(String commitBody, String studentNumber) {
        List<String> found = JsonPath.read(commitBody, "$.credentials[?(@.studentNumber=='" + studentNumber + "')].temporaryCredential");
        return found.get(0);
    }

    // ---- file handling ----

    @Test
    void unusableFilesAreRejectedBeforeAnythingIsStored() throws Exception {
        byte[] oversize = new byte[5 * 1024 * 1024 + 1];
        Arrays.fill(oversize, (byte) 'a');
        StringBuilder many = new StringBuilder(HEADER);
        for (int i = 0; i < 501; i++) many.append("2025-").append(String.format("%04d", i)).append(",L,F,,,,\n");

        Map<String, MvcResult> results = new java.util.LinkedHashMap<>();
        results.put("txt extension", upload(i1, c1, "roster.txt", HEADER.getBytes(StandardCharsets.UTF_8)));
        results.put("pdf extension", upload(i1, c1, "roster.pdf", "%PDF-1.4".getBytes(StandardCharsets.UTF_8)));
        results.put("empty", upload(i1, c1, "roster.csv", new byte[0]));
        results.put("binary csv", upload(i1, c1, "roster.csv", new byte[] {'a', ',', 0, 1, 2}));
        results.put("not utf-8", upload(i1, c1, "roster.csv", new byte[] {(byte) 0xFF, (byte) 0xFE, 'a', 'b'}));
        results.put("fake xlsx", upload(i1, c1, "roster.xlsx", "Student ID,Last Name".getBytes(StandardCharsets.UTF_8)));
        results.put("zip magic but not a workbook", upload(i1, c1, "roster.xlsx", new byte[] {'P', 'K', 3, 4, 0, 0, 0, 0}));
        results.put("missing columns", upload(i1, c1, "roster.csv", "Student ID,Email\n1,a@x.edu\n".getBytes(StandardCharsets.UTF_8)));
        results.put("duplicate columns", upload(i1, c1, "roster.csv", "Student ID,ID Number,Last Name,First Name\n1,2,a,b\n".getBytes(StandardCharsets.UTF_8)));
        results.put("header only", upload(i1, c1, "roster.csv", HEADER.getBytes(StandardCharsets.UTF_8)));
        results.put("too many rows", upload(i1, c1, "roster.csv", many.toString().getBytes(StandardCharsets.UTF_8)));
        results.put("oversize", upload(i1, c1, "roster.csv", oversize));

        Map<String, Integer> expected = new java.util.LinkedHashMap<>();
        expected.put("txt extension", 415);
        expected.put("pdf extension", 415);
        expected.put("oversize", 413);
        results.forEach((name, r) -> assertThat(status(r)).as(name).isEqualTo(expected.getOrDefault(name, 400)));
        assertThat(body(results.get("missing columns"))).contains("Missing required column");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM roster_import", Integer.class)).isZero();
    }

    /** Builds a ZIP that looks like a workbook: a worksheet part of {@code rowsInThousands * 1000} rows plus filler parts. */
    private static byte[] workbookZip(int rowsInThousands, int extraParts) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("[Content_Types].xml"));
            zip.write("<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>".getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new java.util.zip.ZipEntry("xl/worksheets/sheet1.xml"));
            zip.write("<worksheet><sheetData>".getBytes(StandardCharsets.UTF_8));
            StringBuilder chunk = new StringBuilder();
            for (int i = 0; i < 1000; i++) chunk.append("<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>x</t></is></c></row>");
            byte[] chunkBytes = chunk.toString().getBytes(StandardCharsets.UTF_8);
            for (int k = 0; k < rowsInThousands; k++) zip.write(chunkBytes);
            zip.write("</sheetData></worksheet>".getBytes(StandardCharsets.UTF_8));
            for (int e = 0; e < extraParts; e++) {
                zip.putNextEntry(new java.util.zip.ZipEntry("xl/media/part" + e + ".xml"));
                zip.write("<a/>".getBytes(StandardCharsets.UTF_8));
            }
        }
        return out.toByteArray();
    }

    /** A single-row worksheet whose one cell holds {@code megabytes} MB of text (compresses to a few KB). */
    private static byte[] hugeCellZip(int megabytes) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("xl/worksheets/sheet1.xml"));
            zip.write("<worksheet><sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>".getBytes(StandardCharsets.UTF_8));
            byte[] megabyte = new byte[1024 * 1024];
            Arrays.fill(megabyte, (byte) 'a');
            for (int i = 0; i < megabytes; i++) zip.write(megabyte);
            zip.write("</t></is></c></row></sheetData></worksheet>".getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    @Test
    void spreadsheetBombsAreRefusedBeforeAnythingIsLoadedIntoMemory() throws Exception {
        byte[] millionsOfRows = workbookZip(1_500, 0);        // ~90 MB of XML, a few hundred KB zipped
        byte[] hugeCell = hugeCellZip(40);                    // one row, 40 MB of text in a single cell
        byte[] tooManyRows = workbookZip(5, 0);               // 5,000 rows, far below any size limit
        byte[] tooManyParts = workbookZip(0, 300);

        assertThat(millionsOfRows.length).as("it really is a small upload").isLessThan(5 * 1024 * 1024);
        assertThat(hugeCell.length).isLessThan(5 * 1024 * 1024);
        long started = System.nanoTime();
        MvcResult millions = upload(i1, c1, "roster.xlsx", millionsOfRows);
        assertThat(status(millions)).as(body(millions)).isEqualTo(400);
        MvcResult huge = upload(i1, c1, "roster.xlsx", hugeCell);
        assertThat(status(huge)).as(body(huge)).isEqualTo(400);
        assertThat(body(huge)).contains("too large once unpacked");
        assertThat((System.nanoTime() - started) / 1_000_000).as("rejected quickly, in constant memory").isLessThan(15_000);

        MvcResult rows = upload(i1, c1, "roster.xlsx", tooManyRows);
        assertThat(status(rows)).as(body(rows)).isEqualTo(400);
        assertThat(body(rows)).contains("Too many rows");
        MvcResult parts = upload(i1, c1, "roster.xlsx", tooManyParts);
        assertThat(status(parts)).as(body(parts)).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM roster_import", Integer.class)).isZero();
    }

    @Test
    void blankLinesInACsvDoNotCountTowardTheRowLimit() throws Exception {
        StringBuilder csv = new StringBuilder(HEADER).append("2024-0950,Blank,Bea,,,,\n");
        csv.append("\n".repeat(5_000));
        csv.append("2024-0951,Blank,Ben,,,,\n");
        UUID importId = uploadCsv(i1, c1, csv.toString());
        assertThat(field(importId, i1, 2, "action")).isEqualTo("CREATE");
    }

    @Test
    void headerVariantsDelimitersAndByteOrderMarksAreAccepted() throws Exception {
        String csv = "﻿Student Number;Surname;Given Name;Course;Block\n2024-0200;Santos;Rey;BSCS;3C\n2024-0201;\"Dela Cruz, Jr.\";Ann;;\n";
        UUID importId = uploadCsv(i1, c1, csv);
        assertThat(field(importId, i1, 2, "action")).isEqualTo("CREATE");
        assertThat(field(importId, i1, 2, "program")).isEqualTo("BSCS");
        assertThat(field(importId, i1, 3, "lastName")).isEqualTo("Dela Cruz, Jr.");
    }

    @Test
    void xlsxWorkbooksAreReadAsTextAndFormulasAreRejected() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Roster");
            Row header = sheet.createRow(0);
            String[] names = {"Student ID", "Last Name", "First Name", "Email"};
            for (int c = 0; c < names.length; c++) header.createCell(c).setCellValue(names[c]);
            Row numeric = sheet.createRow(1);
            numeric.createCell(0).setCellValue(20240300d);       // spreadsheet apps store IDs as numbers
            numeric.createCell(1).setCellValue("Tan");
            numeric.createCell(2).setCellValue("Lea");
            Row formula = sheet.createRow(2);
            formula.createCell(0).setCellValue("20240301");
            formula.createCell(1).setCellFormula("\"Evil\"&\"Name\"");
            formula.createCell(2).setCellValue("Bob");
            wb.write(out);
        }
        MvcResult r = upload(i1, c1, "roster.xlsx", out.toByteArray());
        assertThat(status(r)).as(body(r)).isEqualTo(201);
        UUID importId = UUID.fromString(JsonPath.read(body(r), "$.id"));
        assertThat(field(importId, i1, 2, "action")).isEqualTo("CREATE");
        assertThat(field(importId, i1, 2, "studentNumber")).isEqualTo("20240300");
        assertThat(field(importId, i1, 3, "action")).isEqualTo("INVALID");
        assertThat((String) field(importId, i1, 3, "message")).contains("formula");

        MvcResult committed = call(i1, HttpMethod.POST, "/api/v1/roster-imports/" + importId + "/commit", null);
        assertThat((Integer) JsonPath.read(body(committed), "$.created")).isEqualTo(1);
        assertThat(countUsersLike("2024030")).isEqualTo(1);
    }

    @Test
    void largeImportsCommitWithDistinctCredentials() throws Exception {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 0; i < 100; i++) csv.append("2026-").append(String.format("%04d", i)).append(",Last").append(i).append(",First,,,,\n");
        UUID importId = uploadCsv(i1, c1, csv.toString());
        MvcResult committed = call(i1, HttpMethod.POST, "/api/v1/roster-imports/" + importId + "/commit", null);
        assertThat(status(committed)).isEqualTo(200);
        List<String> secrets = JsonPath.read(body(committed), "$.credentials[*].temporaryCredential");
        assertThat(new HashSet<>(secrets)).hasSize(100);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM temp_credential", Integer.class)).isEqualTo(100);
    }

    // ---- state changes ----

    @Test
    void commitRefusesAndWritesNothingWhenTheWorldChangedSincePreview() throws Exception {
        UUID importId = uploadCsv(i1, c1, HEADER + "2024-0500,Newman,Neil,,,,\n2024-0501,Other,Olga,,,,\n");
        // Someone creates the same student number between preview and commit.
        user("neil@x.edu", "PARTICIPANT", "ACTIVE", "Newman", "Neil", "2024-0500");

        MvcResult commit = call(i1, HttpMethod.POST, "/api/v1/roster-imports/" + importId + "/commit", null);
        assertThat(status(commit)).isEqualTo(409);
        assertThat(body(commit)).contains("upload the file again");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE student_number = '2024-0501'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM temp_credential", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM roster_import WHERE id = ?", String.class, importId)).isEqualTo("PREVIEW");
    }

    @Test
    void cancelledAndArchivedStatesBlockFurtherChanges() throws Exception {
        UUID importId = uploadCsv(i1, c1, HEADER + "2024-0600,Cancel,Cora,,,,\n");
        String base = "/api/v1/roster-imports/" + importId;
        assertThat(status(call(i1, HttpMethod.DELETE, base, null))).isEqualTo(204);
        assertThat(status(call(i1, HttpMethod.DELETE, base, null))).as("idempotent").isEqualTo(204);
        assertThat(status(call(i1, HttpMethod.POST, base + "/commit", null))).isEqualTo(409);
        assertThat(status(call(i1, HttpMethod.PUT, base + "/resolutions", resolutions(2, "SKIP")))).isEqualTo(409);
        assertThat((String) JsonPath.read(body(call(i1, HttpMethod.GET, base, null)), "$.importInfo.status")).isEqualTo("CANCELLED");
        assertThat(countUsersLike("2024-0600")).isZero();

        UUID second = uploadCsv(i1, c1, HEADER + "2024-0601,Late,Lena,,,,\n");
        jdbc.update("UPDATE academic_class SET status = 'ARCHIVED' WHERE id = ?", c1);
        assertThat(status(call(i1, HttpMethod.POST, "/api/v1/roster-imports/" + second + "/commit", null))).isEqualTo(409);
        assertThat(status(upload(i1, c1, "roster.csv", (HEADER + "2024-0602,X,Y,,,,\n").getBytes(StandardCharsets.UTF_8)))).isEqualTo(409);
        assertThat(countUsersLike("2024-060")).isZero();
    }

    // ---- negative authorization matrix ----

    @Test
    void onlyStaffOfTheClassCanTouchRosterImportsAndOutsidersChangeNothing() throws Exception {
        UUID importId = uploadCsv(i1, c1, HEADER + "2024-0700,Matrix,Mia,,,,\n");
        String base = "/api/v1/roster-imports/" + importId;
        String missing = "/api/v1/roster-imports/" + UUID.randomUUID();
        String detailOfMissing = JsonPath.read(body(call(admin, HttpMethod.GET, missing, null)), "$.detail");

        // Instructors of other classes, unassigned coordinators, unrelated and enrolled students: all 404 on the import.
        for (UUID outsider : List.of(i2, i3, outsiderStudent, ben)) {
            assertThat(status(call(outsider, HttpMethod.GET, base, null))).as("detail as " + outsider).isEqualTo(404);
            assertThat((String) JsonPath.read(body(call(outsider, HttpMethod.GET, base, null)), "$.detail")).isEqualTo(detailOfMissing);
            assertThat(status(call(outsider, HttpMethod.PUT, base + "/resolutions", resolutions(2, "SKIP")))).as("resolve").isEqualTo(404);
            assertThat(status(call(outsider, HttpMethod.POST, base + "/commit", null))).as("commit").isEqualTo(404);
            assertThat(status(call(outsider, HttpMethod.DELETE, base, null))).as("cancel").isEqualTo(404);
        }
        // Class-level endpoints: invisible class -> 404, visible-but-not-staff (enrolled student) -> 403.
        for (UUID outsider : List.of(i2, i3, outsiderStudent)) {
            assertThat(status(upload(outsider, c1, "roster.csv", (HEADER + "2024-0701,A,B,,,,\n").getBytes(StandardCharsets.UTF_8)))).isEqualTo(404);
            assertThat(status(call(outsider, HttpMethod.GET, "/api/v1/workspaces/" + c1 + "/roster-imports", null))).isEqualTo(404);
        }
        assertThat(status(upload(ben, c1, "roster.csv", (HEADER + "2024-0701,A,B,,,,\n").getBytes(StandardCharsets.UTF_8)))).isEqualTo(403);
        assertThat(status(call(ben, HttpMethod.GET, "/api/v1/workspaces/" + c1 + "/roster-imports", null))).isEqualTo(403);

        // Anonymous.
        assertThat(status(call(null, HttpMethod.GET, base, null))).isEqualTo(401);
        assertThat(status(call(null, HttpMethod.POST, base + "/commit", null))).isEqualTo(401);
        assertThat(status(upload(null, c1, "roster.csv", HEADER.getBytes(StandardCharsets.UTF_8)))).isEqualTo(401);

        // Nothing happened: still one pending import, no accounts, no enrollments, no resolutions.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM roster_import", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM roster_import WHERE id = ?", String.class, importId)).isEqualTo("PREVIEW");
        assertThat(countUsersLike("2024-07")).isZero();

        // Staff of the class (and admins) can.
        assertThat(status(call(i1, HttpMethod.GET, base, null))).isEqualTo(200);
        assertThat(status(call(admin, HttpMethod.GET, base, null))).isEqualTo(200);
        assertThat(status(call(i1, HttpMethod.GET, "/api/v1/workspaces/" + c1 + "/roster-imports", null))).isEqualTo(200);
        // Imports of one class are not reachable through another class's staff, even with a valid session.
        assertThat(status(call(i2, HttpMethod.GET, "/api/v1/workspaces/" + c2 + "/roster-imports", null))).isEqualTo(200);
        assertThat((List<Object>) JsonPath.read(body(call(i2, HttpMethod.GET, "/api/v1/workspaces/" + c2 + "/roster-imports", null)), "$")).isEmpty();
    }

    // ---- reissue of lost credentials ----

    @Test
    void staffCanReissueALostCredentialOnlyForPendingStudentsOfTheirClass() throws Exception {
        UUID importId = uploadCsv(i1, c1, HEADER + "2024-0800,Lost,Lia,,,,\n");
        MvcResult committed = call(i1, HttpMethod.POST, "/api/v1/roster-imports/" + importId + "/commit", null);
        String oldSecret = credentialOf(body(committed), "2024-0800");
        UUID lia = UUID.fromString(((List<String>) JsonPath.read(body(committed), "$.credentials[*].participantId")).get(0));
        String url = "/api/v1/workspaces/" + c1 + "/enrollments/" + lia + "/temporary-credential";

        for (UUID denied : List.of(i2, i3, outsiderStudent)) {
            assertThat(status(call(denied, HttpMethod.POST, url, null))).as("outsider " + denied).isEqualTo(404);
        }
        assertThat(status(call(ben, HttpMethod.POST, url, null))).as("enrolled student").isEqualTo(403);
        assertThat(status(call(null, HttpMethod.POST, url, null))).isEqualTo(401);
        assertThat(status(call(i1, HttpMethod.POST, "/api/v1/workspaces/" + c1 + "/enrollments/" + ana + "/temporary-credential", null)))
            .as("not enrolled in this class").isEqualTo(404);
        assertThat(status(call(i1, HttpMethod.POST, "/api/v1/workspaces/" + c1 + "/enrollments/" + ben + "/temporary-credential", null)))
            .as("already activated").isEqualTo(409);

        MvcResult reissued = call(i1, HttpMethod.POST, url, null);
        assertThat(status(reissued)).isEqualTo(200);
        assertThat(reissued.getResponse().getHeader("Cache-Control")).contains("no-store");
        String newSecret = JsonPath.read(body(reissued), "$.secret");
        assertThat(newSecret).isNotEqualTo(oldSecret);

        String check = "/api/v1/auth/activation/check";
        assertThat(status(call(null, HttpMethod.POST, check, "{\"identifier\":\"2024-0800\",\"temporaryCredential\":\"" + oldSecret + "\"}"))).isEqualTo(401);
        assertThat(status(call(null, HttpMethod.POST, check, "{\"identifier\":\"2024-0800\",\"temporaryCredential\":\"" + newSecret + "\"}"))).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM temp_credential WHERE user_id = ? AND invalidated_at IS NULL", Integer.class, lia)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'TEMP_CREDENTIAL_REISSUED'", Integer.class)).isEqualTo(1);

        jdbc.update("UPDATE academic_class SET status = 'ARCHIVED' WHERE id = ?", c1);
        assertThat(status(call(i1, HttpMethod.POST, url, null))).isEqualTo(409);
    }

    @Test
    void rosterImportHelperSetsAreDisjoint() {
        Set<UUID> ids = new HashSet<>(List.of(admin, i1, i2, i3, emailOwner, ana, ben, carl, dina, gone, outsiderStudent));
        assertThat(ids).hasSize(11);
    }
}
