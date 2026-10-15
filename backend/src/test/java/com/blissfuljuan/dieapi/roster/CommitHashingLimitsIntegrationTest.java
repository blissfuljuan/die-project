package com.blissfuljuan.dieapi.roster;

import com.blissfuljuan.dieapi.PostgresIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Security review M2: the bulk Argon2 hashing of a roster commit must not hold a connection, the import row
 * lock or the class lock while it runs, and the number of commits hashing at once must be bounded
 * (one per user, a global cap). Hashing is made artificially slow with a spy so the phases are observable.
 */
@TestPropertySource(properties = {"die.credentials.hash-threads=2", "die.roster.max-concurrent-commits=1"})
class CommitHashingLimitsIntegrationTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct horse battery staple";
    private static final String HEADER = "Student ID,Last Name,First Name\n";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @MockitoSpyBean PasswordEncoder passwordEncoder;

    final ExecutorService pool = Executors.newFixedThreadPool(4);
    UUID i1, i2, c1, c2;
    Cookie cookie1, cookie2;

    @BeforeEach
    void seed() throws Exception {
        i1 = instructor("i1@x.edu");
        i2 = instructor("i2@x.edu");
        c1 = clazz("CS1", i1);
        c2 = clazz("CS2", i2);
        cookie1 = login("i1@x.edu");
        cookie2 = login("i2@x.edu");
    }

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    private UUID instructor(String email) {
        return jdbc.queryForObject("""
            INSERT INTO app_user (role, status, last_name, first_name, email, email_verified_at, password_hash)
            VALUES ('INSTRUCTOR', 'ACTIVE', ?, 'T', ?, now(), ?) RETURNING id
            """, UUID.class, email.substring(0, 2), email, passwordEncoder.encode(PASSWORD));
    }

    private UUID clazz(String code, UUID instructor) {
        UUID id = jdbc.queryForObject("""
            INSERT INTO academic_class (academic_year, semester, subject_code, subject_name, section)
            VALUES ('2025-2026', '1st', ?, 'S', 'A') RETURNING id
            """, UUID.class, code);
        jdbc.update("INSERT INTO class_instructor (class_id, user_id) VALUES (?, ?)", id, instructor);
        return id;
    }

    private Cookie login(String email) throws Exception {
        MvcResult r = mvc.perform(request(HttpMethod.POST, "/api/v1/auth/login").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return r.getResponse().getHeaders("Set-Cookie").stream().filter(h -> h.startsWith("SESSION="))
            .map(h -> new Cookie("SESSION", h.substring("SESSION=".length(), h.indexOf(';')))).findFirst().orElseThrow();
    }

    private UUID uploadRoster(Cookie cookie, UUID classId, String prefix, int students) throws Exception {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 0; i < students; i++) csv.append(prefix).append('-').append(i).append(",Last").append(i).append(",First\n");
        MvcResult r = mvc.perform(multipart("/api/v1/classes/" + classId + "/roster-imports")
            .file(new MockMultipartFile("file", "roster.csv", "text/csv", csv.toString().getBytes(StandardCharsets.UTF_8)))
            .with(csrf()).cookie(cookie)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        return UUID.fromString(JsonPath.read(r.getResponse().getContentAsString(), "$.id"));
    }

    private MvcResult commit(Cookie cookie, UUID importId) throws Exception {
        return mvc.perform(request(HttpMethod.POST, "/api/v1/roster-imports/" + importId + "/commit").with(csrf()).cookie(cookie)).andReturn();
    }

    private static int status(MvcResult r) { return r.getResponse().getStatus(); }

    /** Every Argon2 hash from now on takes at least this long. Must be called after the fixtures were hashed. */
    private void slowHashing(long millis) {
        doAnswer(invocation -> {
            Thread.sleep(millis);
            return invocation.callRealMethod();
        }).when(passwordEncoder).encode(any(CharSequence.class));
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    // ---- hashing holds no database resources ----

    @Test
    void hashingRunsOutsideTheTransactionSoNoLocksAreHeldWhileItIsSlow() throws Exception {
        UUID importId = uploadRoster(cookie1, c1, "A", 6);
        slowHashing(400);                                           // 6 hashes on 2 threads: about 1.3 s

        Future<MvcResult> commit = pool.submit(() -> commit(cookie1, importId));
        Thread.sleep(700);
        assertThat(commit.isDone()).as("the commit is still hashing").isFalse();

        // While it hashes, nothing it needs is locked: these would fail with 55P03 if the old design held them.
        tx().executeWithoutResult(s -> {
            jdbc.queryForObject("SELECT id FROM academic_class WHERE id = ? FOR UPDATE NOWAIT", UUID.class, c1);
            jdbc.queryForObject("SELECT id FROM roster_import WHERE id = ? FOR UPDATE NOWAIT", UUID.class, importId);
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE student_number LIKE 'A-%'", Integer.class))
            .as("nothing is written until hashing is done").isZero();

        MvcResult result = commit.get(30, TimeUnit.SECONDS);
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(200);
        List<String> secrets = JsonPath.read(result.getResponse().getContentAsString(), "$.credentials[*].temporaryCredential");
        assertThat(new HashSet<>(secrets)).hasSize(6);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM temp_credential WHERE secret_hash LIKE '$argon2%'", Integer.class)).isEqualTo(6);
    }

    // ---- admission control ----

    @Test
    void oneCommitPerUserAndAGlobalCapKeepOtherWorkAliveAndRefusedCommitsChangeNothing() throws Exception {
        UUID running = uploadRoster(cookie1, c1, "R", 4);
        UUID second = uploadRoster(cookie1, c1, "S", 3);
        UUID other = uploadRoster(cookie2, c2, "T", 3);
        slowHashing(600);                                           // 4 hashes on 2 threads: about 1.3 s

        Future<MvcResult> first = pool.submit(() -> commit(cookie1, running));
        Thread.sleep(400);
        assertThat(first.isDone()).isFalse();

        MvcResult sameUser = commit(cookie1, second);
        assertThat(status(sameUser)).isEqualTo(429);
        assertThat(sameUser.getResponse().getContentAsString()).contains("already running");
        MvcResult otherUser = commit(cookie2, other);
        assertThat(status(otherUser)).as("global cap of 1").isEqualTo(429);
        assertThat(otherUser.getResponse().getContentAsString()).contains("busy");

        // Refused means untouched.
        for (UUID refused : List.of(second, other)) {
            assertThat(jdbc.queryForObject("SELECT status FROM roster_import WHERE id = ?", String.class, refused)).isEqualTo("PREVIEW");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE student_number LIKE 'S-%' OR student_number LIKE 'T-%'", Integer.class)).isZero();
        // Everyone else keeps working while the pool is busy hashing (login needs the database and a free connection).
        assertThat(login("i2@x.edu")).isNotNull();

        assertThat(status(first.get(30, TimeUnit.SECONDS))).isEqualTo(200);

        // After the running commit finished, the refused ones simply retry.
        assertThat(status(commit(cookie1, second))).isEqualTo(200);
        assertThat(status(commit(cookie2, other))).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM temp_credential", Integer.class)).isEqualTo(10);
    }

    @Test
    void aFailedOrRefusedCommitReleasesItsPermit() throws Exception {
        UUID importId = uploadRoster(cookie1, c1, "F", 2);
        // Conflicting state makes the write phase fail (409) after hashing has happened.
        jdbc.update("INSERT INTO app_user (role, status, student_number, last_name, first_name) VALUES ('STUDENT', 'PENDING_ACTIVATION', 'F-0', 'Last0', 'First')");
        assertThat(status(commit(cookie1, importId))).isEqualTo(409);
        // The same user can commit again straight away: the permit was released.
        UUID fresh = uploadRoster(cookie1, c1, "G", 2);
        assertThat(status(commit(cookie1, fresh))).isEqualTo(200);
    }
}
