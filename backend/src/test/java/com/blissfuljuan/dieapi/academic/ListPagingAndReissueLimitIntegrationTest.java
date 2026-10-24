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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/** Roster and project lists are paged (with totals) and the temporary-credential re-issue is rate limited. */
class ListPagingAndReissueLimitIntegrationTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct horse battery staple";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;

    UUID coordinator, workspace;
    final List<UUID> participants = new ArrayList<>();
    Cookie session;

    @BeforeEach
    void seed() throws Exception {
        coordinator = jdbc.queryForObject("""
            INSERT INTO app_user (role, status, last_name, first_name, email, email_verified_at, password_hash)
            VALUES ('COORDINATOR', 'ACTIVE', 'Reyes', 'Ana', 'coord@example.edu', now(), ?) RETURNING id
            """, UUID.class, encoder.encode(PASSWORD));
        workspace = jdbc.queryForObject("""
            WITH c AS (INSERT INTO academic_class (academic_year, semester, subject_code, subject_name, section, owner_id)
            VALUES ('2025-2026', '1st', 'CS101', 'Subject', 'A', ?) RETURNING id)
            INSERT INTO class_instructor (class_id, user_id) SELECT id, ? FROM c RETURNING class_id
            """, UUID.class, coordinator, coordinator);
        for (int i = 1; i <= 5; i++) {
            UUID id = jdbc.queryForObject("""
                INSERT INTO app_user (role, status, student_number, last_name, first_name, created_by)
                VALUES ('PARTICIPANT', 'PENDING_ACTIVATION', ?, ?, 'Pat', ?) RETURNING id
                """, UUID.class, "S-" + i, "Last" + i, coordinator);
            jdbc.update("INSERT INTO enrollment (class_id, student_id) VALUES (?, ?)", workspace, id);
            participants.add(id);
        }
        session = login("coord@example.edu");
    }

    private Cookie login(String email) throws Exception {
        MvcResult r = mvc.perform(request(HttpMethod.POST, "/api/v1/auth/login").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        String header = r.getResponse().getHeaders("Set-Cookie").stream().filter(h -> h.startsWith("SESSION=")).findFirst().orElseThrow();
        return new Cookie("SESSION", header.substring("SESSION=".length(), header.indexOf(';')));
    }

    private MvcResult call(HttpMethod method, String url, String json) throws Exception {
        MockHttpServletRequestBuilder b = request(method, url).with(csrf()).cookie(session);
        if (json != null) b.contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(b).andReturn();
    }

    private static String body(MvcResult r) throws Exception { return r.getResponse().getContentAsString(); }

    private String base() { return "/api/v1/workspaces/" + workspace; }

    // ---- roster paging ----

    @Test
    void rosterIsPagedWithATotal() throws Exception {
        MvcResult second = call(HttpMethod.GET, base() + "/enrollments?size=2&page=1", null);
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat((List<String>) JsonPath.read(body(second), "$.items[*].studentNumber")).containsExactly("S-3", "S-4");
        assertThat((Integer) JsonPath.read(body(second), "$.page")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(body(second), "$.size")).isEqualTo(2);
        assertThat(((Number) JsonPath.read(body(second), "$.total")).longValue()).isEqualTo(5);

        MvcResult last = call(HttpMethod.GET, base() + "/enrollments?size=2&page=2", null);
        assertThat((List<String>) JsonPath.read(body(last), "$.items[*].studentNumber")).containsExactly("S-5");
        assertThat((List<Object>) JsonPath.read(body(call(HttpMethod.GET, base() + "/enrollments?size=2&page=9", null)), "$.items")).isEmpty();
    }

    @Test
    void absurdPageSizesAreClamped() throws Exception {
        assertThat((Integer) JsonPath.read(body(call(HttpMethod.GET, base() + "/enrollments?size=0", null)), "$.size")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(body(call(HttpMethod.GET, base() + "/enrollments?size=100000", null)), "$.size")).isEqualTo(200);
        assertThat((Integer) JsonPath.read(body(call(HttpMethod.GET, base() + "/enrollments?page=-3", null)), "$.page")).isZero();
        assertThat((Integer) JsonPath.read(body(call(HttpMethod.GET, base() + "/projects?size=100000", null)), "$.size")).isEqualTo(100);
    }

    // ---- project list paging ----

    @Test
    void projectListIsPagedAndRolesAreCorrectForEveryRow() throws Exception {
        for (int i = 0; i < 3; i++) {
            MvcResult created = call(HttpMethod.POST, base() + "/projects",
                "{\"kind\":\"INDIVIDUAL\",\"title\":\"Solo " + i + "\",\"participantId\":\"" + participants.get(i) + "\"}");
            assertThat(created.getResponse().getStatus()).isEqualTo(201);
        }
        MvcResult page = call(HttpMethod.GET, base() + "/projects?size=2&page=0", null);
        assertThat((List<String>) JsonPath.read(body(page), "$.items[*].title")).containsExactly("Solo 0", "Solo 1");
        assertThat(((Number) JsonPath.read(body(page), "$.total")).longValue()).isEqualTo(3);
        assertThat((List<String>) JsonPath.read(body(page), "$.items[*].myRole")).containsOnly("STAFF");

        MvcResult rest = call(HttpMethod.GET, base() + "/projects?size=2&page=1", null);
        assertThat((List<String>) JsonPath.read(body(rest), "$.items[*].title")).containsExactly("Solo 2");
    }

    // ---- credential re-issue limits ----

    private int reissue(UUID participant) throws Exception {
        return call(HttpMethod.POST, base() + "/enrollments/" + participant + "/temporary-credential", null).getResponse().getStatus();
    }

    @Test
    void aParticipantsCredentialCanBeReissuedOnlyAFewTimesPerHour() throws Exception {
        UUID target = participants.get(0);
        for (int i = 0; i < 3; i++) {
            assertThat(reissue(target)).as("re-issue " + (i + 1)).isEqualTo(200);
        }
        MvcResult refused = call(HttpMethod.POST, base() + "/enrollments/" + target + "/temporary-credential", null);
        assertThat(refused.getResponse().getStatus()).isEqualTo(429);
        assertThat(body(refused)).contains("CREDENTIAL_REISSUE_LIMIT");
        // Another participant is not affected by that limit.
        assertThat(reissue(participants.get(1))).isEqualTo(200);
    }

    @Test
    void oneCoordinatorCannotSweepManyParticipants() throws Exception {
        for (int i = 0; i < 30; i++) {
            jdbc.update("INSERT INTO audit_log (actor_user_id, action, resource_type, resource_id) VALUES (?, 'TEMP_CREDENTIAL_REISSUED', 'user', ?)",
                coordinator, UUID.randomUUID().toString());
        }
        assertThat(reissue(participants.get(2))).isEqualTo(429);
        // Nothing was issued by the refused call.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM temp_credential WHERE user_id = ?", Integer.class, participants.get(2))).isZero();
    }
}
