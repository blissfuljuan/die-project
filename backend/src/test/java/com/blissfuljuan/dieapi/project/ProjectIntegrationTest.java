package com.blissfuljuan.dieapi.project;

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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Projects, teams, invitations and the negative-authorization matrix.
 * C1 is a student-formed class (team size 2..3); C2 is an instructor-assigned class.
 */
class ProjectIntegrationTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct horse battery staple";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;

    UUID admin, i1, i2, a1, a2, a3, a4, a5, b1, b2, out;
    UUID c1, c2;
    final Map<UUID, Cookie> sessions = new HashMap<>();

    @BeforeEach
    void seed() {
        sessions.clear();
        admin = user("admin@x.edu", "ADMIN", "Admin", "Root", null);
        i1 = user("i1@x.edu", "INSTRUCTOR", "One", "Ian", null);
        i2 = user("i2@x.edu", "INSTRUCTOR", "Two", "Ivy", null);
        a1 = user("a1@x.edu", "STUDENT", "Aa", "One", "S-A1");
        a2 = user("a2@x.edu", "STUDENT", "Aa", "Two", "S-A2");
        a3 = user("a3@x.edu", "STUDENT", "Aa", "Three", "S-A3");
        a4 = user("a4@x.edu", "STUDENT", "Aa", "Four", "S-A4");
        a5 = user("a5@x.edu", "STUDENT", "Aa", "Five", "S-A5");
        b1 = user("b1@x.edu", "STUDENT", "Bb", "One", "S-B1");
        b2 = user("b2@x.edu", "STUDENT", "Bb", "Two", "S-B2");
        out = user("out@x.edu", "STUDENT", "Out", "Side", "S-OUT");
        c1 = clazz("CS101", i1, "STUDENT_FORMED", 2, 3, false);
        c2 = clazz("CS102", i2, "INSTRUCTOR_ASSIGNED", 1, 5, false);
        for (UUID s : List.of(a1, a2, a3, a4, a5)) enroll(c1, s);
        for (UUID s : List.of(b1, b2)) enroll(c2, s);
    }

    // ---- fixtures ----

    private UUID user(String email, String role, String last, String first, String number) {
        return jdbc.queryForObject("""
            INSERT INTO app_user (role, status, student_number, last_name, first_name, email, email_verified_at, password_hash)
            VALUES (?, 'ACTIVE', ?, ?, ?, ?, now(), ?) RETURNING id
            """, UUID.class, role, number, last, first, email, encoder.encode(PASSWORD));
    }

    private UUID clazz(String code, UUID instructor, String formation, int min, int max, boolean mayChange) {
        UUID id = jdbc.queryForObject("""
            INSERT INTO academic_class (academic_year, semester, subject_code, subject_name, section,
                   team_formation, team_min_size, team_max_size, students_may_change_teams)
            VALUES ('2025-2026', '1st', ?, 'Subject', 'A', ?, ?, ?, ?) RETURNING id
            """, UUID.class, code, formation, min, max, mayChange);
        jdbc.update("INSERT INTO class_instructor (class_id, user_id) VALUES (?, ?)", id, instructor);
        return id;
    }

    private void enroll(UUID classId, UUID student) {
        jdbc.update("INSERT INTO enrollment (class_id, student_id) VALUES (?, ?)", classId, student);
    }

    /** SQL fixture: a project whose first listed member is the representative (TEAM) or sole member (INDIVIDUAL). */
    private UUID project(UUID classId, String kind, String title, UUID... members) {
        UUID id = jdbc.queryForObject("""
            INSERT INTO project (class_id, title, kind, team_name) VALUES (?, ?, ?, CASE WHEN ? = 'TEAM' THEN ? END) RETURNING id
            """, UUID.class, classId, title, kind, kind, title);
        for (int i = 0; i < members.length; i++) {
            String role = "TEAM".equals(kind) && i == 0 ? "REPRESENTATIVE" : "MEMBER";
            jdbc.update("INSERT INTO project_member (project_id, user_id, role) VALUES (?, ?, ?)", id, members[i], role);
        }
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

    private MvcResult call(UUID actor, HttpMethod method, String path, String json) throws Exception {
        MockHttpServletRequestBuilder req = request(method, path);
        if (method != HttpMethod.GET) req.with(csrf());
        if (actor != null) req.cookie(as(actor));
        if (json != null) req.contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(req).andReturn();
    }

    private static int status(MvcResult r) { return r.getResponse().getStatus(); }
    private static String body(MvcResult r) throws Exception { return r.getResponse().getContentAsString(); }

    private long version(UUID projectId) {
        return jdbc.queryForObject("SELECT version FROM project WHERE id = ?", Long.class, projectId);
    }

    private List<UUID> currentMembers(UUID projectId) {
        return jdbc.queryForList("SELECT user_id FROM project_member WHERE project_id = ? AND left_at IS NULL ORDER BY joined_at, user_id",
            UUID.class, projectId);
    }

    private String roleOf(UUID projectId, UUID user) {
        return jdbc.queryForObject("SELECT role FROM project_member WHERE project_id = ? AND user_id = ? AND left_at IS NULL",
            String.class, projectId, user);
    }

    private UUID createTeam(UUID student, String title) throws Exception {
        MvcResult r = call(student, HttpMethod.POST, "/api/v1/classes/" + c1 + "/projects",
            "{\"kind\":\"TEAM\",\"title\":\"" + title + "\",\"teamName\":\"" + title + "\"}");
        assertThat(status(r)).as(body(r)).isEqualTo(201);
        return UUID.fromString(JsonPath.read(body(r), "$.id"));
    }

    private UUID invite(UUID actor, UUID projectId, UUID student) throws Exception {
        MvcResult r = call(actor, HttpMethod.POST, "/api/v1/projects/" + projectId + "/invitations", "{\"studentId\":\"" + student + "\"}");
        assertThat(status(r)).as(body(r)).isEqualTo(201);
        return UUID.fromString(JsonPath.read(body(r), "$.id"));
    }

    // ================= student-formed teams =================

    @Test
    void studentFormedTeamLifecycleWithInvitationsAndSizeLimits() throws Exception {
        MvcResult created = call(a1, HttpMethod.POST, "/api/v1/classes/" + c1 + "/projects",
            "{\"kind\":\"TEAM\",\"title\":\"Capstone\",\"teamName\":\"Team Rocket\",\"description\":\"d\"}");
        assertThat(status(created)).isEqualTo(201);
        UUID team = UUID.fromString(JsonPath.read(body(created), "$.id"));
        assertThat((String) JsonPath.read(body(created), "$.myRole")).isEqualTo("REPRESENTATIVE");
        assertThat((Boolean) JsonPath.read(body(created), "$.meetsMinSize")).isFalse();
        assertThat((List<String>) JsonPath.read(body(created), "$.members[*].role")).containsExactly("REPRESENTATIVE");

        // The representative sees which classmates can still be invited: no self, nobody on a team, nobody outside the class.
        MvcResult eligible = call(a1, HttpMethod.GET, "/api/v1/classes/" + c1 + "/eligible-classmates", null);
        assertThat((List<String>) JsonPath.read(body(eligible), "$[*].id")).containsExactlyInAnyOrder(
            a2.toString(), a3.toString(), a4.toString(), a5.toString());
        assertThat(body(eligible)).doesNotContain("S-A2").doesNotContain("studentNumber");

        UUID inv2 = invite(a1, team, a2);
        assertThat((List<String>) JsonPath.read(body(call(a2, HttpMethod.GET, "/api/v1/invitations", null)), "$[*].id"))
            .containsExactly(inv2.toString());
        MvcResult accepted = call(a2, HttpMethod.POST, "/api/v1/invitations/" + inv2 + "/accept", null);
        assertThat(status(accepted)).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(body(accepted), "$.meetsMinSize")).isTrue();
        assertThat(currentMembers(team)).containsExactly(a1, a2);

        UUID inv3 = invite(a1, team, a3);                       // 2 members + 0 pending < 3
        assertThat(status(call(a1, HttpMethod.POST, "/api/v1/projects/" + team + "/invitations",
            "{\"studentId\":\"" + a4 + "\"}"))).as("members + pending invitations reach the maximum").isEqualTo(409);
        assertThat(status(call(a3, HttpMethod.POST, "/api/v1/invitations/" + inv3 + "/accept", null))).isEqualTo(200);
        assertThat(currentMembers(team)).containsExactly(a1, a2, a3);
        assertThat(status(call(a1, HttpMethod.POST, "/api/v1/projects/" + team + "/invitations",
            "{\"studentId\":\"" + a4 + "\"}"))).as("full team").isEqualTo(409);

        // Representative hands over; the former representative loses edit rights, the new one gains them.
        assertThat(status(call(a1, HttpMethod.PUT, "/api/v1/projects/" + team + "/representative", "{\"studentId\":\"" + a2 + "\"}"))).isEqualTo(200);
        assertThat(roleOf(team, a1)).isEqualTo("MEMBER");
        assertThat(roleOf(team, a2)).isEqualTo("REPRESENTATIVE");
        assertThat(status(call(a1, HttpMethod.PATCH, "/api/v1/projects/" + team,
            "{\"version\":" + version(team) + ",\"description\":\"nope\"}"))).isEqualTo(403);
        assertThat(status(call(a2, HttpMethod.PATCH, "/api/v1/projects/" + team,
            "{\"version\":" + version(team) + ",\"description\":\"yes\"}"))).isEqualTo(200);
        assertThat(status(call(a2, HttpMethod.PUT, "/api/v1/projects/" + team + "/representative", "{\"studentId\":\"" + a4 + "\"}")))
            .as("representative must be a current member").isEqualTo(400);

        // Members see names but never classmates' student numbers; staff see them. History keeps role periods.
        assertThat(body(call(a3, HttpMethod.GET, "/api/v1/projects/" + team, null))).doesNotContain("S-A1").doesNotContain("S-A2");
        assertThat(body(call(i1, HttpMethod.GET, "/api/v1/projects/" + team, null))).contains("S-A1").contains("S-A2");
        MvcResult history = call(i1, HttpMethod.GET, "/api/v1/projects/" + team + "/membership-history", null);
        assertThat((List<String>) JsonPath.read(body(history), "$[?(@.userId=='" + a1 + "')].role"))
            .containsExactly("REPRESENTATIVE", "MEMBER");
    }

    @Test
    void invitationRulesProtectTheRosterAndTheInvitee() throws Exception {
        UUID team = createTeam(a1, "Alpha");
        String url = "/api/v1/projects/" + team + "/invitations";

        assertThat(status(call(a1, HttpMethod.POST, url, "{\"studentId\":\"" + b1 + "\"}"))).as("student of another class").isEqualTo(404);
        assertThat(status(call(a1, HttpMethod.POST, url, "{\"studentId\":\"" + out + "\"}"))).as("not enrolled anywhere").isEqualTo(404);
        assertThat(status(call(a1, HttpMethod.POST, url, "{\"studentId\":\"" + UUID.randomUUID() + "\"}"))).as("unknown id").isEqualTo(404);
        assertThat(status(call(a1, HttpMethod.POST, url, "{\"studentId\":\"" + i1 + "\"}"))).as("an instructor").isEqualTo(404);
        assertThat(status(call(a1, HttpMethod.POST, url, "{\"studentId\":\"" + a1 + "\"}"))).as("self").isEqualTo(400);
        UUID other = createTeam(a2, "Beta");
        assertThat(status(call(a1, HttpMethod.POST, url, "{\"studentId\":\"" + a2 + "\"}"))).as("already on a team").isEqualTo(409);

        UUID inv = invite(a1, team, a3);
        assertThat(status(call(a1, HttpMethod.POST, url, "{\"studentId\":\"" + a3 + "\"}"))).as("duplicate pending").isEqualTo(409);

        // Only the invitee can act on an invitation; everybody else gets the same 404 as for a missing one.
        for (UUID other3 : List.of(a4, a5, a1, i1, admin, out)) {
            assertThat(status(call(other3, HttpMethod.POST, "/api/v1/invitations/" + inv + "/accept", null))).as("accept as " + other3).isEqualTo(404);
            assertThat(status(call(other3, HttpMethod.POST, "/api/v1/invitations/" + inv + "/decline", null))).as("decline as " + other3).isEqualTo(404);
        }
        assertThat(status(call(a3, HttpMethod.POST, "/api/v1/invitations/" + UUID.randomUUID() + "/accept", null))).isEqualTo(404);
        assertThat(currentMembers(team)).containsExactly(a1);

        // Joining another team in the meantime invalidates the invitation.
        UUID inv2 = invite(a2, other, a3);
        assertThat(status(call(a3, HttpMethod.POST, "/api/v1/invitations/" + inv2 + "/accept", null))).isEqualTo(200);
        assertThat(status(call(a3, HttpMethod.POST, "/api/v1/invitations/" + inv + "/accept", null))).isEqualTo(409);
        assertThat((List<Object>) JsonPath.read(body(call(a3, HttpMethod.GET, "/api/v1/invitations", null)), "$")).isEmpty();

        // Declined, cancelled and expired invitations cannot be accepted.
        UUID declined = invite(a1, team, a4);
        assertThat(status(call(a4, HttpMethod.POST, "/api/v1/invitations/" + declined + "/decline", null))).isEqualTo(204);
        assertThat(status(call(a4, HttpMethod.POST, "/api/v1/invitations/" + declined + "/accept", null))).isEqualTo(409);
        UUID cancelled = invite(a1, team, a4);
        assertThat(status(call(a1, HttpMethod.DELETE, url + "/" + cancelled, null))).isEqualTo(204);
        assertThat(status(call(a4, HttpMethod.POST, "/api/v1/invitations/" + cancelled + "/accept", null))).isEqualTo(409);
        UUID expired = invite(a1, team, a5);
        jdbc.update("UPDATE team_invitation SET expires_at = now() - interval '1 minute' WHERE id = ?", expired);
        assertThat((List<Object>) JsonPath.read(body(call(a5, HttpMethod.GET, "/api/v1/invitations", null)), "$")).isEmpty();
        assertThat(status(call(a5, HttpMethod.POST, "/api/v1/invitations/" + expired + "/accept", null))).isEqualTo(409);
        assertThat(currentMembers(team)).containsExactly(a1);
    }

    @Test
    void acceptingOneInvitationCancelsTheOthersAndTheClassPolicyIsRechecked() throws Exception {
        UUID t1 = createTeam(a1, "One");
        UUID t2 = createTeam(a2, "Two");
        UUID inviteFromOne = invite(a1, t1, a3);
        UUID inviteFromTwo = invite(a2, t2, a3);
        assertThat(status(call(a3, HttpMethod.POST, "/api/v1/invitations/" + inviteFromOne + "/accept", null))).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM team_invitation WHERE id = ?", String.class, inviteFromTwo)).isEqualTo("CANCELLED");

        // Instructor switches the class to assigned teams: outstanding invitations stop working.
        UUID inv = invite(a1, t1, a4);
        jdbc.update("UPDATE academic_class SET team_formation = 'INSTRUCTOR_ASSIGNED' WHERE id = ?", c1);
        assertThat(status(call(a4, HttpMethod.POST, "/api/v1/invitations/" + inv + "/accept", null))).isEqualTo(409);
        assertThat(status(call(a1, HttpMethod.POST, "/api/v1/projects/" + t1 + "/invitations", "{\"studentId\":\"" + a5 + "\"}"))).isEqualTo(403);
    }

    // ================= creation rules =================

    @Test
    void studentsCanOnlyCreateOneTeamAndOnlyWhereTheClassAllowsIt() throws Exception {
        String team = "{\"kind\":\"TEAM\",\"title\":\"T\"}";
        assertThat(status(call(b1, HttpMethod.POST, "/api/v1/classes/" + c2 + "/projects", team))).as("assigned-team class").isEqualTo(403);
        assertThat(status(call(a1, HttpMethod.POST, "/api/v1/classes/" + c1 + "/projects", "{\"kind\":\"INDIVIDUAL\",\"title\":\"T\"}"))).as("students cannot create individual projects").isEqualTo(403);
        assertThat(status(call(out, HttpMethod.POST, "/api/v1/classes/" + c1 + "/projects", team))).as("not in the class").isEqualTo(404);
        assertThat(status(call(b1, HttpMethod.POST, "/api/v1/classes/" + c1 + "/projects", team))).as("other class").isEqualTo(404);
        assertThat(status(call(i2, HttpMethod.POST, "/api/v1/classes/" + c1 + "/projects", team))).as("instructor of another class").isEqualTo(404);
        assertThat(status(call(null, HttpMethod.POST, "/api/v1/classes/" + c1 + "/projects", team))).isEqualTo(401);
        assertThat(status(call(a1, HttpMethod.POST, "/api/v1/classes/" + c1 + "/projects", "{\"kind\":\"TEAM\"}"))).as("title required").isEqualTo(400);
        assertThat(status(call(a1, HttpMethod.POST, "/api/v1/classes/" + c1 + "/projects", "{\"kind\":\"CLUB\",\"title\":\"T\"}"))).isEqualTo(400);

        createTeam(a1, "Mine");
        assertThat(status(call(a1, HttpMethod.POST, "/api/v1/classes/" + c1 + "/projects", team))).as("second team").isEqualTo(409);
        jdbc.update("UPDATE academic_class SET status = 'ARCHIVED' WHERE id = ?", c1);
        assertThat(status(call(a2, HttpMethod.POST, "/api/v1/classes/" + c1 + "/projects", team))).as("archived class").isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM project", Integer.class)).isEqualTo(1);
    }

    @Test
    void staffAssignTeamsAndIndividualProjectsWithinTheClassPolicy() throws Exception {
        String url = "/api/v1/classes/" + c1 + "/projects";
        MvcResult created = call(i1, HttpMethod.POST, url,
            "{\"kind\":\"TEAM\",\"title\":\"Assigned\",\"memberIds\":[\"" + a1 + "\",\"" + a2 + "\"],\"representativeId\":\"" + a2 + "\"}");
        assertThat(status(created)).isEqualTo(201);
        UUID team = UUID.fromString(JsonPath.read(body(created), "$.id"));
        assertThat((String) JsonPath.read(body(created), "$.myRole")).isEqualTo("STAFF");
        assertThat(roleOf(team, a2)).isEqualTo("REPRESENTATIVE");
        assertThat(body(created)).contains("S-A1");

        assertThat(status(call(i1, HttpMethod.POST, url, "{\"kind\":\"TEAM\",\"title\":\"Small\",\"memberIds\":[\"" + a3 + "\"]}"))).as("below minimum").isEqualTo(400);
        assertThat(status(call(i1, HttpMethod.POST, url, "{\"kind\":\"TEAM\",\"title\":\"Small\",\"memberIds\":[\"" + a3 + "\"],\"allowSizeException\":true}"))).as("exception granted").isEqualTo(201);
        assertThat(status(call(admin, HttpMethod.POST, url, "{\"kind\":\"TEAM\",\"title\":\"Big\",\"memberIds\":[\"" + a4 + "\",\"" + a5 + "\",\"" + b1 + "\",\"" + out + "\"]}"))).as("above maximum").isEqualTo(400);
        assertThat(status(call(i1, HttpMethod.POST, url, "{\"kind\":\"TEAM\",\"title\":\"X\",\"memberIds\":[\"" + a4 + "\",\"" + b1 + "\"]}"))).as("student of another class").isEqualTo(400);
        assertThat(status(call(i1, HttpMethod.POST, url, "{\"kind\":\"TEAM\",\"title\":\"X\",\"memberIds\":[\"" + a4 + "\",\"" + a1 + "\"]}"))).as("already on a team").isEqualTo(409);
        assertThat(status(call(i1, HttpMethod.POST, url, "{\"kind\":\"TEAM\",\"title\":\"X\",\"memberIds\":[\"" + a4 + "\",\"" + a5 + "\"],\"representativeId\":\"" + a1 + "\"}"))).as("representative not a member").isEqualTo(400);

        MvcResult individual = call(i1, HttpMethod.POST, url, "{\"kind\":\"INDIVIDUAL\",\"title\":\"Solo\",\"studentId\":\"" + a4 + "\"}");
        assertThat(status(individual)).isEqualTo(201);
        assertThat((List<String>) JsonPath.read(body(individual), "$.members[*].role")).containsExactly("MEMBER");
        assertThat((Boolean) JsonPath.read(body(individual), "$.meetsMinSize")).isTrue();
        assertThat(status(call(i1, HttpMethod.POST, url, "{\"kind\":\"INDIVIDUAL\",\"title\":\"Solo2\",\"studentId\":\"" + a1 + "\"}"))).isEqualTo(409);
        assertThat(status(call(i1, HttpMethod.POST, url, "{\"kind\":\"INDIVIDUAL\",\"title\":\"Solo3\"}"))).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'PROJECT_CREATED'", Integer.class)).isEqualTo(3);
    }

    // ================= membership management =================

    @Test
    void staffReplaceMembersWithOptimisticLockingAndFullHistory() throws Exception {
        UUID team = project(c1, "TEAM", "Managed", a1, a2);
        String url = "/api/v1/projects/" + team + "/members";
        long v = version(team);

        assertThat(status(call(i1, HttpMethod.PUT, url, "{\"memberIds\":[\"" + a1 + "\",\"" + a3 + "\"]}"))).as("version required").isEqualTo(400);
        assertThat(status(call(i1, HttpMethod.PUT, url, "{\"version\":" + (v + 3) + ",\"memberIds\":[\"" + a1 + "\",\"" + a3 + "\"]}"))).as("stale").isEqualTo(409);
        assertThat(status(call(i1, HttpMethod.PUT, url, "{\"version\":" + v + ",\"memberIds\":[\"" + a1 + "\"]}"))).as("below minimum").isEqualTo(400);
        assertThat(status(call(i1, HttpMethod.PUT, url, "{\"version\":" + v + ",\"memberIds\":[\"" + a1 + "\",\"" + b1 + "\"]}"))).as("other class").isEqualTo(400);
        UUID elsewhere = project(c1, "TEAM", "Elsewhere", a4, a5);
        assertThat(status(call(i1, HttpMethod.PUT, url, "{\"version\":" + v + ",\"memberIds\":[\"" + a1 + "\",\"" + a4 + "\"]}"))).as("on another project").isEqualTo(409);
        assertThat(currentMembers(team)).containsExactly(a1, a2);
        assertThat(currentMembers(elsewhere)).containsExactly(a4, a5);

        MvcResult replaced = call(i1, HttpMethod.PUT, url,
            "{\"version\":" + v + ",\"memberIds\":[\"" + a1 + "\",\"" + a3 + "\"],\"representativeId\":\"" + a3 + "\"}");
        assertThat(status(replaced)).isEqualTo(200);
        assertThat(currentMembers(team)).containsExactlyInAnyOrder(a1, a3);
        assertThat(roleOf(team, a3)).isEqualTo("REPRESENTATIVE");
        assertThat(roleOf(team, a1)).isEqualTo("MEMBER");
        assertThat(version(team)).isGreaterThan(v);
        assertThat(status(call(i1, HttpMethod.PUT, url, "{\"version\":" + v + ",\"memberIds\":[\"" + a1 + "\",\"" + a3 + "\"]}"))).as("old version").isEqualTo(409);

        // Nothing is ever deleted: a2 and a1's earlier representative period remain in the history.
        MvcResult history = call(admin, HttpMethod.GET, "/api/v1/projects/" + team + "/membership-history", null);
        assertThat((List<String>) JsonPath.read(body(history), "$[?(@.userId=='" + a2 + "')].role")).containsExactly("MEMBER");
        assertThat((List<String>) JsonPath.read(body(history), "$[?(@.userId=='" + a1 + "')].role")).containsExactly("REPRESENTATIVE", "MEMBER");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM project_member WHERE project_id = ? AND user_id = ? AND left_at IS NOT NULL",
            Integer.class, team, a2)).isEqualTo(1);

        // Removed students become free to join another project.
        assertThat(status(call(i1, HttpMethod.POST, "/api/v1/classes/" + c1 + "/projects",
            "{\"kind\":\"INDIVIDUAL\",\"title\":\"Solo\",\"studentId\":\"" + a2 + "\"}"))).isEqualTo(201);
    }

    @Test
    void individualProjectsAreReassignedNeverEmptiedAndCannotBeLeft() throws Exception {
        UUID solo = project(c1, "INDIVIDUAL", "Solo", a3);
        String members = "/api/v1/projects/" + solo + "/members";
        long v = version(solo);
        assertThat(status(call(i1, HttpMethod.PUT, members, "{\"version\":" + v + ",\"memberIds\":[\"" + a3 + "\",\"" + a4 + "\"]}"))).isEqualTo(400);
        assertThat(status(call(i1, HttpMethod.PUT, members, "{\"version\":" + v + ",\"memberIds\":[]}"))).isEqualTo(400);
        assertThat(status(call(i1, HttpMethod.PUT, members, "{\"version\":" + v + ",\"memberIds\":[\"" + a4 + "\"]}"))).isEqualTo(200);
        assertThat(currentMembers(solo)).containsExactly(a4);
        assertThat(status(call(a4, HttpMethod.POST, "/api/v1/projects/" + solo + "/leave", null))).isEqualTo(409);
        assertThat(status(call(i1, HttpMethod.PUT, "/api/v1/projects/" + solo + "/representative", "{\"studentId\":\"" + a4 + "\"}"))).isEqualTo(409);
        assertThat(status(call(a4, HttpMethod.PATCH, "/api/v1/projects/" + solo, "{\"version\":" + version(solo) + ",\"title\":\"x\"}")))
            .as("individual projects are managed by staff").isEqualTo(403);
    }

    @Test
    void archivingFreezesTheProjectAndReleasesItsStudents() throws Exception {
        UUID team = project(c1, "TEAM", "Done", a1, a2);
        UUID pending = jdbc.queryForObject("INSERT INTO team_invitation (project_id, invited_user_id, invited_by, expires_at) VALUES (?, ?, ?, now() + interval '1 day') RETURNING id",
            UUID.class, team, a3, a1);
        MvcResult archived = call(i1, HttpMethod.POST, "/api/v1/projects/" + team + "/archive", null);
        assertThat(status(archived)).isEqualTo(200);
        assertThat((String) JsonPath.read(body(archived), "$.status")).isEqualTo("ARCHIVED");
        assertThat(status(call(i1, HttpMethod.POST, "/api/v1/projects/" + team + "/archive", null))).as("idempotent").isEqualTo(200);
        assertThat(status(call(a1, HttpMethod.PATCH, "/api/v1/projects/" + team, "{\"version\":" + version(team) + ",\"title\":\"x\"}"))).isEqualTo(409);
        assertThat(status(call(i1, HttpMethod.PUT, "/api/v1/projects/" + team + "/members",
            "{\"version\":" + version(team) + ",\"memberIds\":[\"" + a1 + "\",\"" + a2 + "\"]}"))).isEqualTo(409);
        assertThat(status(call(a3, HttpMethod.POST, "/api/v1/invitations/" + pending + "/accept", null))).isEqualTo(409);
        assertThat(status(call(a1, HttpMethod.GET, "/api/v1/projects/" + team, null))).as("history stays readable").isEqualTo(200);
        createTeam(a1, "Fresh start");
    }

    // ================= leaving and class changes =================

    @Test
    void leavingFollowsClassPolicyAndPassesTheRepresentativeRoleOn() throws Exception {
        UUID team = project(c1, "TEAM", "Leavers", a1, a2, a3);
        assertThat(status(call(a2, HttpMethod.POST, "/api/v1/projects/" + team + "/leave", null))).as("policy forbids").isEqualTo(403);
        assertThat(status(call(i1, HttpMethod.POST, "/api/v1/projects/" + team + "/leave", null))).as("staff do not leave").isEqualTo(403);
        jdbc.update("UPDATE academic_class SET students_may_change_teams = TRUE WHERE id = ?", c1);

        assertThat(status(call(a1, HttpMethod.POST, "/api/v1/projects/" + team + "/leave", null))).isEqualTo(204);   // the representative
        assertThat(currentMembers(team)).containsExactlyInAnyOrder(a2, a3);
        assertThat(roleOf(team, a2)).as("earliest remaining member succeeds").isEqualTo("REPRESENTATIVE");
        assertThat(status(call(a1, HttpMethod.GET, "/api/v1/projects/" + team, null))).as("no longer a member").isEqualTo(404);

        assertThat(status(call(a3, HttpMethod.POST, "/api/v1/projects/" + team + "/leave", null))).isEqualTo(204);
        assertThat(status(call(a2, HttpMethod.POST, "/api/v1/projects/" + team + "/leave", null))).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT status FROM project WHERE id = ?", String.class, team)).as("empty teams are archived").isEqualTo("ARCHIVED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action IN ('PROJECT_REPRESENTATIVE_SUCCEEDED','PROJECT_ARCHIVED_EMPTY')", Integer.class)).isEqualTo(2);
    }

    @Test
    void removingAStudentFromTheClassEndsTheirTeamMembershipAndInvitations() throws Exception {
        UUID team = project(c1, "TEAM", "Shrinking", a1, a2, a3);
        UUID inv = jdbc.queryForObject("INSERT INTO team_invitation (project_id, invited_user_id, invited_by, expires_at) VALUES (?, ?, ?, now() + interval '1 day') RETURNING id",
            UUID.class, team, a4, a1);
        UUID otherTeam = project(c1, "TEAM", "Other", a5);

        assertThat(status(call(i1, HttpMethod.DELETE, "/api/v1/classes/" + c1 + "/enrollments/" + a1, null))).isEqualTo(204);   // the representative
        assertThat(currentMembers(team)).containsExactlyInAnyOrder(a2, a3);
        assertThat(roleOf(team, a2)).isEqualTo("REPRESENTATIVE");
        assertThat(status(call(a1, HttpMethod.GET, "/api/v1/projects/" + team, null))).isEqualTo(404);

        assertThat(status(call(i1, HttpMethod.DELETE, "/api/v1/classes/" + c1 + "/enrollments/" + a4, null))).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT status FROM team_invitation WHERE id = ?", String.class, inv)).isEqualTo("CANCELLED");

        assertThat(status(call(i1, HttpMethod.DELETE, "/api/v1/classes/" + c1 + "/enrollments/" + a5, null))).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT status FROM project WHERE id = ?", String.class, otherTeam)).isEqualTo("ARCHIVED");
        assertThat(currentMembers(team)).containsExactlyInAnyOrder(a2, a3);
    }

    @Test
    void lowerTeamMaximumIsRefusedWhileATeamWouldExceedIt() throws Exception {
        project(c1, "TEAM", "Trio", a1, a2, a3);
        long classVersion = jdbc.queryForObject("SELECT version FROM academic_class WHERE id = ?", Long.class, c1);
        assertThat(status(call(i1, HttpMethod.PATCH, "/api/v1/classes/" + c1, "{\"version\":" + classVersion + ",\"teamMaxSize\":2}"))).isEqualTo(409);
        assertThat(status(call(i1, HttpMethod.PATCH, "/api/v1/classes/" + c1, "{\"version\":" + classVersion + ",\"teamMaxSize\":4}"))).isEqualTo(200);
    }

    // ================= negative authorization matrix =================

    private record Op(String name, HttpMethod method, String path, String body) {}

    @Test
    void everyActorWithoutTheRightCannotSeeOrChangeAProjectAndNothingChanges() throws Exception {
        UUID p1 = project(c1, "TEAM", "Matrix", a1, a2);
        UUID p2 = project(c1, "INDIVIDUAL", "Solo", a3);
        UUID q1 = project(c2, "TEAM", "Foreign", b1);
        UUID inv = jdbc.queryForObject("INSERT INTO team_invitation (project_id, invited_user_id, invited_by, expires_at) VALUES (?, ?, ?, now() + interval '1 day') RETURNING id",
            UUID.class, p1, a4, a1);
        long v = version(p1);
        String base = "/api/v1/projects/" + p1;
        List<Op> ops = List.of(
            new Op("get", HttpMethod.GET, base, null),
            new Op("patch", HttpMethod.PATCH, base, "{\"version\":" + v + ",\"title\":\"HACKED\"}"),
            new Op("archive", HttpMethod.POST, base + "/archive", null),
            new Op("members", HttpMethod.PUT, base + "/members", "{\"version\":" + v + ",\"memberIds\":[\"" + a1 + "\",\"" + a5 + "\"]}"),
            new Op("representative", HttpMethod.PUT, base + "/representative", "{\"studentId\":\"" + a2 + "\"}"),
            new Op("invite", HttpMethod.POST, base + "/invitations", "{\"studentId\":\"" + a5 + "\"}"),
            new Op("cancel invitation", HttpMethod.DELETE, base + "/invitations/" + inv, null),
            new Op("history", HttpMethod.GET, base + "/membership-history", null),
            new Op("leave", HttpMethod.POST, base + "/leave", null));

        // Anonymous.
        for (Op op : ops) assertThat(status(call(null, op.method(), op.path(), op.body()))).as("anonymous " + op.name()).isEqualTo(401);

        // The project does not exist for: staff of another class, students of another class, students without a class,
        // and classmates who are not on the team.
        for (UUID outsider : List.of(i2, b1, b2, out, a3, a4, a5)) {
            for (Op op : ops) {
                assertThat(status(call(outsider, op.method(), op.path(), op.body()))).as("%s as %s", op.name(), outsider).isEqualTo(404);
            }
        }
        String missingDetail = JsonPath.read(body(call(admin, HttpMethod.GET, "/api/v1/projects/" + UUID.randomUUID(), null)), "$.detail");
        assertThat((String) JsonPath.read(body(call(a5, HttpMethod.GET, base, null)), "$.detail")).isEqualTo(missingDetail);

        // A plain member can see the project but nothing else.
        for (Op op : ops) {
            int expected = op.name().equals("get") ? 200 : 403;
            assertThat(status(call(a2, op.method(), op.path(), op.body()))).as("member: " + op.name()).isEqualTo(expected);
        }
        // The representative may not use staff-only operations either.
        for (String staffOnly : List.of("archive", "members", "history", "leave")) {
            Op op = ops.stream().filter(o -> o.name().equals(staffOnly)).findFirst().orElseThrow();
            assertThat(status(call(a1, op.method(), op.path(), op.body()))).as("representative: " + op.name()).isEqualTo(403);
        }
        // Foreign and individual projects stay invisible too.
        assertThat(status(call(a1, HttpMethod.GET, "/api/v1/projects/" + p2, null))).isEqualTo(404);
        assertThat(status(call(a1, HttpMethod.GET, "/api/v1/projects/" + q1, null))).isEqualTo(404);
        assertThat(status(call(i1, HttpMethod.GET, "/api/v1/projects/" + q1, null))).isEqualTo(404);

        // Nothing changed.
        assertThat(version(p1)).isEqualTo(v);
        assertThat(jdbc.queryForObject("SELECT title FROM project WHERE id = ?", String.class, p1)).isEqualTo("Matrix");
        assertThat(jdbc.queryForObject("SELECT status FROM project WHERE id = ?", String.class, p1)).isEqualTo("ACTIVE");
        assertThat(currentMembers(p1)).containsExactly(a1, a2);
        assertThat(roleOf(p1, a1)).isEqualTo("REPRESENTATIVE");
        assertThat(jdbc.queryForObject("SELECT status FROM team_invitation WHERE id = ?", String.class, inv)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM team_invitation WHERE project_id = ?", Integer.class, p1)).isEqualTo(1);

        // What the allowed roles can do.
        assertThat(status(call(i1, HttpMethod.GET, base + "/membership-history", null))).isEqualTo(200);
        assertThat(status(call(admin, HttpMethod.GET, base + "/membership-history", null))).isEqualTo(200);
        assertThat(status(call(a1, HttpMethod.DELETE, base + "/invitations/" + inv, null))).isEqualTo(204);
        // (members + pending invitations are capped at the class maximum, so invite after freeing the slot)
        assertThat(status(call(a1, HttpMethod.POST, base + "/invitations", "{\"studentId\":\"" + a5 + "\"}"))).isEqualTo(201);
    }

    @Test
    void projectListsAndInvitationsAreScopedToWhatTheCallerMayKnow() throws Exception {
        UUID p1 = project(c1, "TEAM", "Matrix", a1, a2);
        UUID p2 = project(c1, "INDIVIDUAL", "Solo", a3);
        project(c2, "TEAM", "Foreign", b1);
        String list = "/api/v1/classes/" + c1 + "/projects";

        assertThat((List<String>) JsonPath.read(body(call(a1, HttpMethod.GET, list, null)), "$[*].id")).containsExactly(p1.toString());
        assertThat((List<String>) JsonPath.read(body(call(a3, HttpMethod.GET, list, null)), "$[*].id")).containsExactly(p2.toString());
        assertThat((List<Object>) JsonPath.read(body(call(a5, HttpMethod.GET, list, null)), "$")).isEmpty();
        assertThat((List<String>) JsonPath.read(body(call(i1, HttpMethod.GET, list, null)), "$[*].id")).containsExactlyInAnyOrder(p1.toString(), p2.toString());
        assertThat((List<String>) JsonPath.read(body(call(admin, HttpMethod.GET, list, null)), "$[*].id")).containsExactlyInAnyOrder(p1.toString(), p2.toString());
        for (UUID outsider : List.of(i2, b1, out)) {
            assertThat(status(call(outsider, HttpMethod.GET, list, null))).as("list as " + outsider).isEqualTo(404);
        }
        assertThat(status(call(null, HttpMethod.GET, list, null))).isEqualTo(401);

        // Student numbers are staff-only data.
        assertThat(body(call(a2, HttpMethod.GET, list, null))).doesNotContain("S-A1");
        assertThat(body(call(i1, HttpMethod.GET, list, null))).contains("S-A1");

        // Classmate lookup: unavailable where the instructor assigns teams, invisible to outsiders.
        assertThat(status(call(b1, HttpMethod.GET, "/api/v1/classes/" + c2 + "/eligible-classmates", null))).isEqualTo(403);
        assertThat(status(call(out, HttpMethod.GET, "/api/v1/classes/" + c1 + "/eligible-classmates", null))).isEqualTo(404);
        assertThat(status(call(b1, HttpMethod.GET, "/api/v1/classes/" + c1 + "/eligible-classmates", null))).isEqualTo(404);
        assertThat(status(call(i1, HttpMethod.GET, "/api/v1/classes/" + c1 + "/eligible-classmates", null))).isEqualTo(200);
        assertThat(status(call(i2, HttpMethod.GET, "/api/v1/classes/" + c1 + "/eligible-classmates", null))).isEqualTo(404);

        // Staff have no invitations of their own.
        assertThat((List<Object>) JsonPath.read(body(call(i1, HttpMethod.GET, "/api/v1/invitations", null)), "$")).isEmpty();
    }

    @Test
    void invitationsInAssignedClassesCannotBeSentByStudents() throws Exception {
        UUID team = project(c2, "TEAM", "Assigned", b1);
        assertThat(status(call(b1, HttpMethod.POST, "/api/v1/projects/" + team + "/invitations", "{\"studentId\":\"" + b2 + "\"}"))).isEqualTo(403);
        assertThat(status(call(i2, HttpMethod.POST, "/api/v1/projects/" + team + "/invitations", "{\"studentId\":\"" + b2 + "\"}")))
            .as("staff may still invite").isEqualTo(201);
    }
}
