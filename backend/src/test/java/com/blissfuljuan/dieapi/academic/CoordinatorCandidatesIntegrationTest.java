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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/** The "add a coordinator" picker: only people who may add coordinators can search, and only a narrow slice comes back. */
class CoordinatorCandidatesIntegrationTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct horse battery staple";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;

    UUID owner, helper, participant, workspace;

    @BeforeEach
    void seed() {
        owner = user("COORDINATOR", "ACTIVE", "owner@example.edu", "Owner", "Olivia", null);
        helper = user("COORDINATOR", "ACTIVE", "helper@example.edu", "Helper", "Hugo", null);
        user("COORDINATOR", "ACTIVE", "lopez.maria@example.edu", "Lopez", "Maria", null);
        user("COORDINATOR", "PENDING_ACTIVATION", "lopez.new@example.edu", "Lopez", "Nico", null);
        user("COORDINATOR", "DEACTIVATED", "lopez.gone@example.edu", "Lopez", "Gone", null);
        user("ADMIN", "ACTIVE", "lopez.admin@example.edu", "Lopez", "Admin", null);
        participant = user("PARTICIPANT", "ACTIVE", "lopez.student@example.edu", "Lopez", "Student", "S-1");
        workspace = jdbc.queryForObject("""
            WITH c AS (INSERT INTO academic_class (academic_year, semester, subject_code, subject_name, section, owner_id)
            VALUES ('2025-2026', '1st', 'CS101', 'Subject', 'A', ?) RETURNING id),
            i AS (INSERT INTO class_instructor (class_id, user_id) SELECT id, ? FROM c)
            INSERT INTO class_instructor (class_id, user_id, capabilities) SELECT id, ?, ARRAY['MANAGE_ROSTER'] FROM c RETURNING class_id
            """, UUID.class, owner, owner, helper);
        jdbc.update("INSERT INTO enrollment (class_id, student_id) VALUES (?, ?)", workspace, participant);
    }

    private UUID user(String role, String status, String email, String last, String first, String studentNumber) {
        boolean active = !"PENDING_ACTIVATION".equals(status);   // active and deactivated accounts have a password
        return jdbc.queryForObject("""
            INSERT INTO app_user (role, status, student_number, last_name, first_name, email, email_verified_at, password_hash)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
            """, UUID.class, role, status, studentNumber, last, first, email,
            active ? java.sql.Timestamp.from(java.time.Instant.now()) : null, active ? encoder.encode(PASSWORD) : null);
    }

    private MvcResult search(String email, String q) throws Exception {
        MvcResult login = mvc.perform(request(HttpMethod.POST, "/api/v1/auth/login").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")).andReturn();
        String header = login.getResponse().getHeaders("Set-Cookie").stream().filter(h -> h.startsWith("SESSION=")).findFirst().orElseThrow();
        Cookie session = new Cookie("SESSION", header.substring("SESSION=".length(), header.indexOf(';')));
        return mvc.perform(request(HttpMethod.GET, "/api/v1/workspaces/" + workspace + "/coordinator-candidates")
            .param("q", q).cookie(session)).andReturn();
    }

    @Test
    void ownerFindsOnlyActiveUnassignedCoordinators() throws Exception {
        MvcResult r = search("owner@example.edu", "lop");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        String body = r.getResponse().getContentAsString();
        // Maria (active) and Nico (waiting to activate) can be added; the deactivated one, the admin and the student cannot.
        assertThat((List<String>) JsonPath.read(body, "$[*].email")).containsExactlyInAnyOrder("lopez.maria@example.edu", "lopez.new@example.edu");
    }

    @Test
    void assignedCoordinatorsAreLeftOut() throws Exception {
        assertThat((List<String>) JsonPath.read(search("owner@example.edu", "hel").getResponse().getContentAsString(), "$[*].email")).isEmpty();
    }

    @Test
    void shortOrWildcardQueriesReturnNothing() throws Exception {
        assertThat((List<Object>) JsonPath.read(search("owner@example.edu", "lo").getResponse().getContentAsString(), "$")).isEmpty();
        assertThat((List<Object>) JsonPath.read(search("owner@example.edu", "%%%").getResponse().getContentAsString(), "$")).isEmpty();
        assertThat((List<Object>) JsonPath.read(search("owner@example.edu", "___").getResponse().getContentAsString(), "$")).isEmpty();
    }

    @Test
    void aCoordinatorWithoutTheCoordinatorsCapabilityAndAParticipantAreRefused() throws Exception {
        assertThat(search("helper@example.edu", "lop").getResponse().getStatus()).isEqualTo(403);
        assertThat(search("lopez.student@example.edu", "lop").getResponse().getStatus()).isEqualTo(403);
    }
}
