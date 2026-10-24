package com.blissfuljuan.dieapi.access;

import com.blissfuljuan.dieapi.PostgresIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Authorization safety net. Every /api/v1 endpoint must be listed below with the rule that protects it.
 *
 *  1. A new endpoint without an entry fails the build ("declare its authorization rule here").
 *  2. Anonymous callers must be stopped by the filter chain on every non-public endpoint.
 *  3. For every resource-scoped endpoint, each actor that must NOT have access is tried against real
 *     fixtures with a body that would succeed — it must get exactly the declared 403/404, and the data
 *     must be byte-for-byte unchanged afterwards.
 *  4. Reading endpoints are also called as an allowed actor, proving the fixtures exist (so a 404 for an
 *     outsider is a real denial and not "id not found").
 *
 * When you add an endpoint: add a row to {@link #manifest()}, choose its Rule (add a Rule if none fits),
 * and make the body valid, so that the call would succeed if authorization were missing.
 */
class EndpointAuthorizationMatrixTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct horse battery staple";

    /** How an endpoint is protected. Drives which actors are probed and what they must get back. */
    enum Rule {
        PUBLIC,          // reachable without a session (login, activation, reset, csrf)
        SELF,            // any signed-in user; only touches the caller's own data
        ADMIN,           // @PreAuthorize hasRole('ADMIN')
        ROLE_STAFF,      // @PreAuthorize hasAnyRole('ADMIN','COORDINATOR')
        CLASS_VIEW,      // AccessPolicy.requireClassView
        CLASS_MANAGE,    // AccessPolicy.requireClassManage
        COORDINATORS_MANAGE, // AccessPolicy.requireWorkspaceCapability(MANAGE_COORDINATORS): Owner, delegated coordinators, admins
        OWNER,           // AccessPolicy.requireWorkspaceOwner: the Owner or an administrator (a plain coordinator gets 403)
        IMPORT,          // roster import: staff of its class only, everyone else 404
        PROJECT_VIEW,    // AccessPolicy.requireProjectView
        PROJECT_EDIT,    // AccessPolicy.requireProjectEdit
        PROJECT_STAFF,   // AccessPolicy.requireProjectStaff
        PROJECT_MEMBER,  // team members only (leave)
        INVITEE          // only the invited student
    }

    record Ep(String method, String pattern, Rule rule, Supplier<String> path, Supplier<String> body, boolean multipart) {
        String key() { return method + " " + pattern; }
    }

    record Denial(UUID actor, int status, String label) {}

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping;

    // actors
    UUID admin, iA, iB, iD, sR, sM, sC, sI, sB, sOut;
    // resources
    UUID classA, classB, projectA, importA, invitationA, testJobA;
    final Map<UUID, Cookie> sessions = new HashMap<>();
    final Map<UUID, String> names = new HashMap<>();

    @BeforeEach
    void fixtures() {
        sessions.clear();
        admin = user("admin", "ADMIN", null);
        iA = user("iA", "COORDINATOR", null);
        iB = user("iB", "COORDINATOR", null);
        iD = user("iD", "COORDINATOR", null); // second coordinator of class A: NOT the Owner, default capabilities only
        sR = user("sR", "PARTICIPANT", "S-R");
        sM = user("sM", "PARTICIPANT", "S-M");
        sC = user("sC", "PARTICIPANT", "S-C");
        sI = user("sI", "PARTICIPANT", "S-I");
        sB = user("sB", "PARTICIPANT", "S-B");
        sOut = user("sOut", "PARTICIPANT", "S-OUT");
        classA = clazz("CSA", iA);
        classB = clazz("CSB", iB);
        jdbc.update("INSERT INTO class_instructor (class_id, user_id) VALUES (?, ?)", classA, iD);
        for (UUID s : List.of(sR, sM, sC, sI)) jdbc.update("INSERT INTO enrollment (class_id, student_id) VALUES (?, ?)", classA, s);
        jdbc.update("INSERT INTO enrollment (class_id, student_id) VALUES (?, ?)", classB, sB);
        projectA = jdbc.queryForObject("INSERT INTO project (class_id, title, kind, team_name) VALUES (?, 'Team A', 'TEAM', 'Team A') RETURNING id", UUID.class, classA);
        jdbc.update("INSERT INTO project_member (project_id, user_id, role) VALUES (?, ?, 'REPRESENTATIVE')", projectA, sR);
        jdbc.update("INSERT INTO project_member (project_id, user_id, role) VALUES (?, ?, 'MEMBER')", projectA, sM);
        invitationA = jdbc.queryForObject("INSERT INTO team_invitation (project_id, invited_user_id, invited_by, expires_at) VALUES (?, ?, ?, now() + interval '1 day') RETURNING id",
            UUID.class, projectA, sI, sR);
        testJobA = jdbc.queryForObject("INSERT INTO test_job (requested_by, correlation_id) VALUES (?, 'fixture-corr-1') RETURNING id", UUID.class, admin);
        importA = jdbc.queryForObject("INSERT INTO roster_import (class_id, uploaded_by, file_name, file_sha256) VALUES (?, ?, 'r.csv', 'x') RETURNING id", UUID.class, classA, iA);
        jdbc.update("""
            INSERT INTO roster_import_row (import_id, row_number, student_number, last_name, first_name, action)
            VALUES (?, 2, 'S-NEW', 'New', 'Person', 'CREATE')
            """, importA);
    }

    // ---- the manifest: one row per endpoint ----

    private List<Ep> manifest() {
        String a = "/api/v1/workspaces/" + classA;
        String p = "/api/v1/projects/" + projectA;
        String imp = "/api/v1/roster-imports/" + importA;
        String user = "/api/v1/users/" + sC;
        Supplier<String> none = () -> null;
        List<Ep> m = new ArrayList<>();

        // --- authentication and account recovery (public) / session (self)
        m.add(ep("GET", "/api/v1/auth/csrf", Rule.PUBLIC, "/api/v1/auth/csrf", none));
        m.add(ep("GET", "/api/v1/status", Rule.PUBLIC, "/api/v1/status", none));
        m.add(ep("POST", "/api/v1/auth/login", Rule.PUBLIC, "/api/v1/auth/login", () -> "{\"email\":\"nobody@x.edu\",\"password\":\"x\"}"));
        m.add(ep("POST", "/api/v1/auth/activation/check", Rule.PUBLIC, "/api/v1/auth/activation/check", () -> "{\"identifier\":\"nobody\",\"temporaryCredential\":\"x\"}"));
        m.add(ep("POST", "/api/v1/auth/activation/begin", Rule.PUBLIC, "/api/v1/auth/activation/begin",
            () -> "{\"identifier\":\"nobody\",\"temporaryCredential\":\"x\",\"email\":\"n@x.edu\",\"newPassword\":\"a long enough password\"}"));
        m.add(ep("POST", "/api/v1/auth/activation/verify", Rule.PUBLIC, "/api/v1/auth/activation/verify", () -> "{\"token\":\"nope\"}"));
        m.add(ep("POST", "/api/v1/auth/password-reset/request", Rule.PUBLIC, "/api/v1/auth/password-reset/request", () -> "{\"email\":\"nobody@x.edu\"}"));
        m.add(ep("POST", "/api/v1/auth/password-reset/confirm", Rule.PUBLIC, "/api/v1/auth/password-reset/confirm",
            () -> "{\"token\":\"nope\",\"newPassword\":\"a long enough password\"}"));
        m.add(ep("GET", "/api/v1/auth/me", Rule.SELF, "/api/v1/auth/me", none));
        m.add(ep("POST", "/api/v1/auth/logout", Rule.SELF, "/api/v1/auth/logout", none));
        m.add(ep("POST", "/api/v1/auth/password/change", Rule.SELF, "/api/v1/auth/password/change",
            () -> "{\"currentPassword\":\"x\",\"newPassword\":\"a long enough password\"}"));

        // --- user administration (admin only)
        m.add(ep("GET", "/api/v1/users", Rule.ADMIN, "/api/v1/users", none));
        m.add(ep("POST", "/api/v1/users", Rule.ADMIN, "/api/v1/users",
            () -> "{\"role\":\"COORDINATOR\",\"lastName\":\"Z\",\"firstName\":\"Z\",\"email\":\"zz@x.edu\"}"));
        m.add(ep("GET", "/api/v1/users/{id}", Rule.ADMIN, user, none));
        m.add(ep("PATCH", "/api/v1/users/{id}", Rule.ADMIN, user, () -> "{\"version\":0,\"firstName\":\"X\"}"));
        m.add(ep("POST", "/api/v1/users/{id}/deactivate", Rule.ADMIN, user + "/deactivate", none));
        m.add(ep("POST", "/api/v1/users/{id}/activate", Rule.ADMIN, user + "/activate", none));
        m.add(ep("POST", "/api/v1/users/{id}/reset-credentials", Rule.ADMIN, user + "/reset-credentials", none));

        // --- classes and enrollment
        m.add(ep("GET", "/api/v1/workspaces", Rule.SELF, "/api/v1/workspaces", none));
        m.add(ep("POST", "/api/v1/workspaces", Rule.ROLE_STAFF, "/api/v1/workspaces",
            () -> "{\"academicYear\":\"2030\",\"semester\":\"1st\",\"subjectCode\":\"NEW1\",\"subjectName\":\"New\",\"section\":\"A\",\"ownerId\":\"" + iA + "\"}"));
        m.add(ep("GET", "/api/v1/workspaces/{id}", Rule.CLASS_VIEW, a, none));
        m.add(ep("PATCH", "/api/v1/workspaces/{id}", Rule.CLASS_MANAGE, a, () -> "{\"version\":" + classVersion() + ",\"section\":\"Z\"}"));
        m.add(ep("POST", "/api/v1/workspaces/{id}/archive", Rule.CLASS_MANAGE, a + "/archive", none));
        m.add(ep("POST", "/api/v1/workspaces/{id}/unarchive", Rule.ADMIN, a + "/unarchive", none));
        m.add(ep("GET", "/api/v1/workspaces/{id}/coordinators", Rule.CLASS_MANAGE, a + "/coordinators", none));
        m.add(ep("GET", "/api/v1/workspaces/{id}/coordinator-candidates", Rule.COORDINATORS_MANAGE, a + "/coordinator-candidates?q=abc", none));
        m.add(ep("POST", "/api/v1/workspaces/{id}/coordinators", Rule.COORDINATORS_MANAGE, a + "/coordinators", () -> "{\"userId\":\"" + iB + "\"}"));
        m.add(ep("PATCH", "/api/v1/workspaces/{id}/coordinators/{userId}", Rule.COORDINATORS_MANAGE, a + "/coordinators/" + iD,
            () -> "{\"capabilities\":[\"MANAGE_ROSTER\"]}"));
        m.add(ep("DELETE", "/api/v1/workspaces/{id}/coordinators/{userId}", Rule.COORDINATORS_MANAGE, a + "/coordinators/" + iD, none));
        m.add(ep("PUT", "/api/v1/workspaces/{id}/owner", Rule.OWNER, a + "/owner", () -> "{\"userId\":\"" + iD + "\"}"));
        m.add(ep("GET", "/api/v1/workspaces/{id}/enrollments", Rule.CLASS_MANAGE, a + "/enrollments", none));
        m.add(ep("POST", "/api/v1/workspaces/{id}/enrollments", Rule.CLASS_MANAGE, a + "/enrollments", () -> "{\"studentNumber\":\"S-OUT\"}"));
        m.add(ep("DELETE", "/api/v1/workspaces/{id}/enrollments/{participantId}", Rule.CLASS_MANAGE, a + "/enrollments/" + sC, none));
        m.add(ep("POST", "/api/v1/workspaces/{id}/enrollments/{participantId}/temporary-credential", Rule.CLASS_MANAGE,
            a + "/enrollments/" + sC + "/temporary-credential", none));

        // --- roster import
        m.add(new Ep("POST", "/api/v1/workspaces/{workspaceId}/roster-imports", Rule.CLASS_MANAGE, () -> a + "/roster-imports", none, true));
        m.add(ep("GET", "/api/v1/workspaces/{workspaceId}/roster-imports", Rule.CLASS_MANAGE, a + "/roster-imports", none));
        m.add(ep("GET", "/api/v1/roster-imports/{id}", Rule.IMPORT, imp, none));
        m.add(ep("PUT", "/api/v1/roster-imports/{id}/resolutions", Rule.IMPORT, imp + "/resolutions",
            () -> "{\"resolutions\":[{\"rowNumber\":2,\"resolution\":\"SKIP\"}]}"));
        m.add(ep("POST", "/api/v1/roster-imports/{id}/commit", Rule.IMPORT, imp + "/commit", none));
        m.add(ep("DELETE", "/api/v1/roster-imports/{id}", Rule.IMPORT, imp, none));

        // --- projects, teams and invitations
        m.add(ep("GET", "/api/v1/workspaces/{workspaceId}/projects", Rule.CLASS_VIEW, a + "/projects", none));
        m.add(ep("POST", "/api/v1/workspaces/{workspaceId}/projects", Rule.CLASS_VIEW, a + "/projects", () -> "{\"kind\":\"TEAM\",\"title\":\"T\"}"));
        m.add(ep("GET", "/api/v1/workspaces/{workspaceId}/eligible-participants", Rule.CLASS_VIEW, a + "/eligible-participants", none));
        m.add(ep("GET", "/api/v1/projects/{id}", Rule.PROJECT_VIEW, p, none));
        m.add(ep("PATCH", "/api/v1/projects/{id}", Rule.PROJECT_EDIT, p, () -> "{\"version\":" + projectVersion() + ",\"title\":\"X\"}"));
        m.add(ep("POST", "/api/v1/projects/{id}/archive", Rule.PROJECT_STAFF, p + "/archive", none));
        m.add(ep("PUT", "/api/v1/projects/{id}/members", Rule.PROJECT_STAFF, p + "/members",
            () -> "{\"version\":" + projectVersion() + ",\"memberIds\":[\"" + sR + "\",\"" + sC + "\"]}"));
        m.add(ep("PUT", "/api/v1/projects/{id}/representative", Rule.PROJECT_EDIT, p + "/representative", () -> "{\"participantId\":\"" + sM + "\"}"));
        m.add(ep("GET", "/api/v1/projects/{id}/membership-history", Rule.PROJECT_STAFF, p + "/membership-history", none));
        m.add(ep("POST", "/api/v1/projects/{id}/leave", Rule.PROJECT_MEMBER, p + "/leave", none));
        m.add(ep("POST", "/api/v1/projects/{id}/invitations", Rule.PROJECT_EDIT, p + "/invitations", () -> "{\"participantId\":\"" + sC + "\"}"));
        m.add(ep("DELETE", "/api/v1/projects/{id}/invitations/{invitationId}", Rule.PROJECT_EDIT, p + "/invitations/" + invitationA, none));
        m.add(ep("GET", "/api/v1/invitations", Rule.SELF, "/api/v1/invitations", none));
        m.add(ep("POST", "/api/v1/invitations/{id}/accept", Rule.INVITEE, "/api/v1/invitations/" + invitationA + "/accept", none));
        m.add(ep("POST", "/api/v1/invitations/{id}/decline", Rule.INVITEE, "/api/v1/invitations/" + invitationA + "/decline", none));

        // --- operations: infrastructure smoke test of the async backbone (admin only, not project content)
        m.add(ep("POST", "/api/v1/ops/test-jobs", Rule.ADMIN, "/api/v1/ops/test-jobs", () -> "{\"note\":\"matrix\"}"));
        m.add(ep("GET", "/api/v1/ops/test-jobs/{id}", Rule.ADMIN, "/api/v1/ops/test-jobs/" + testJobA, none));
        m.add(ep("GET", "/api/v1/ops/health", Rule.ADMIN, "/api/v1/ops/health", none));
        return m;
    }

    private static Ep ep(String method, String pattern, Rule rule, String path, Supplier<String> body) {
        return new Ep(method, pattern, rule, () -> path, body, false);
    }

    private long classVersion() { return jdbc.queryForObject("SELECT version FROM academic_class WHERE id = ?", Long.class, classA); }
    private long projectVersion() { return jdbc.queryForObject("SELECT version FROM project WHERE id = ?", Long.class, projectA); }

    /** Actors that must be refused for a rule, with the exact status they must get. */
    private List<Denial> denials(Rule rule) {
        List<Denial> d = new ArrayList<>();
        switch (rule) {
            case ADMIN -> forbid(d, 403, iA, iB, sR, sC, sB, sOut);
            case ROLE_STAFF -> forbid(d, 403, sR, sC, sB, sOut);
            case CLASS_VIEW -> forbid(d, 404, iB, sB, sOut);
            case CLASS_MANAGE -> { forbid(d, 404, iB, sB, sOut); forbid(d, 403, sR, sM, sC, sI); }
            case COORDINATORS_MANAGE, OWNER -> { forbid(d, 404, iB, sB, sOut); forbid(d, 403, sR, sM, sC, sI, iD); }
            case IMPORT -> forbid(d, 404, iB, sB, sOut, sR, sM, sC, sI);
            case PROJECT_VIEW -> forbid(d, 404, iB, sB, sOut, sC, sI);
            case PROJECT_EDIT -> { forbid(d, 404, iB, sB, sOut, sC, sI); forbid(d, 403, sM); }
            case PROJECT_STAFF -> { forbid(d, 404, iB, sB, sOut, sC, sI); forbid(d, 403, sM, sR); }
            case PROJECT_MEMBER -> { forbid(d, 404, iB, sB, sOut, sC, sI); forbid(d, 403, iA, admin); }
            case INVITEE -> forbid(d, 404, admin, iA, iB, sR, sM, sC, sB, sOut);
            case PUBLIC, SELF -> { }
        }
        return d;
    }

    private void forbid(List<Denial> into, int status, UUID... actors) {
        for (UUID a : actors) into.add(new Denial(a, status, names.get(a)));
    }

    /** One actor that IS allowed, used to prove fixtures exist (GET endpoints only). */
    private UUID allowed(Rule rule) {
        return switch (rule) {
            case ADMIN -> admin;
            case ROLE_STAFF, CLASS_MANAGE, COORDINATORS_MANAGE, OWNER, IMPORT, PROJECT_STAFF -> iA;
            case CLASS_VIEW -> sR;
            case PROJECT_VIEW, PROJECT_MEMBER -> sM;
            case PROJECT_EDIT -> sR;
            case INVITEE -> sI;
            case PUBLIC, SELF -> null;
        };
    }

    // ---- tests ----

    @Test
    void everyApiEndpointIsDeclaredWithAnAuthorizationRule() {
        Set<String> actual = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : handlerMapping.getHandlerMethods().entrySet()) {
            Set<String> patterns = e.getKey().getPathPatternsCondition() == null ? Set.of()
                : e.getKey().getPathPatternsCondition().getPatternValues();
            Set<RequestMethod> methods = e.getKey().getMethodsCondition().getMethods();
            for (String pattern : patterns) {
                if (!pattern.startsWith("/api/")) continue;
                assertThat(pattern).as("API paths must live under /api/v1").startsWith("/api/v1/");
                assertThat(methods).as("%s must declare its HTTP method explicitly", pattern).isNotEmpty();
                for (RequestMethod m : methods) actual.add(m + " " + pattern);
            }
        }
        Set<String> declared = new TreeSet<>();
        manifest().forEach(ep -> declared.add(ep.key()));

        Set<String> undeclared = new TreeSet<>(actual);
        undeclared.removeAll(declared);
        Set<String> stale = new TreeSet<>(declared);
        stale.removeAll(actual);
        assertThat(undeclared).as("Endpoints without an authorization rule: add them to manifest() with a Rule and a valid body").isEmpty();
        assertThat(stale).as("manifest() entries that no longer match any endpoint").isEmpty();
    }

    @Test
    void roleGatedRulesMatchTheAnnotationsOnTheHandlers() {
        Map<String, HandlerMethod> byKey = new HashMap<>();
        handlerMapping.getHandlerMethods().forEach((info, hm) -> {
            if (info.getPathPatternsCondition() == null) return;
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                for (RequestMethod m : info.getMethodsCondition().getMethods()) byKey.put(m + " " + pattern, hm);
            }
        });
        for (Ep ep : manifest()) {
            HandlerMethod hm = byKey.get(ep.key());
            String expression = preAuthorize(hm);
            switch (ep.rule()) {
                case ADMIN -> assertThat(expression).as(ep.key()).contains("hasRole('ADMIN')");
                case ROLE_STAFF -> assertThat(expression).as(ep.key()).contains("hasAnyRole('ADMIN','COORDINATOR')");
                default -> assertThat(expression).as(ep.key() + " is " + ep.rule() + ": role gates belong to ADMIN/ROLE_STAFF rules")
                    .doesNotContain("hasRole('ADMIN')");
            }
        }
    }

    private static String preAuthorize(HandlerMethod hm) {
        PreAuthorize onMethod = AnnotatedElementUtils.findMergedAnnotation(hm.getMethod(), PreAuthorize.class);
        PreAuthorize onClass = AnnotatedElementUtils.findMergedAnnotation(hm.getBeanType(), PreAuthorize.class);
        PreAuthorize used = onMethod != null ? onMethod : onClass;
        return used == null ? "" : used.value();
    }

    @Test
    void anonymousCallersAreStoppedByTheFilterChainExceptOnPublicEndpoints() throws Exception {
        List<String> failures = new ArrayList<>();
        for (Ep ep : manifest()) {
            MvcResult r = send(null, ep);
            boolean blockedByFilterChain = r.getResponse().getStatus() == 401
                && r.getResponse().getContentAsString().contains("\"code\":\"AUTHENTICATION_REQUIRED\"");
            if (ep.rule() == Rule.PUBLIC && blockedByFilterChain) failures.add(ep.key() + " should be public but anonymous callers are rejected");
            if (ep.rule() != Rule.PUBLIC && !blockedByFilterChain) {
                failures.add(ep.key() + " is " + ep.rule() + " but an anonymous call got " + r.getResponse().getStatus());
            }
        }
        assertThat(failures).isEmpty();
    }

    @Test
    void fixturesExistSoDenialsAreRealDenials() throws Exception {
        List<String> failures = new ArrayList<>();
        for (Ep ep : manifest()) {
            UUID actor = allowed(ep.rule());
            if (actor == null || !ep.method().equals("GET")) continue;
            int status = send(actor, ep).getResponse().getStatus();
            if (status != 200) failures.add(ep.key() + " as an allowed actor returned " + status);
        }
        assertThat(failures).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM roster_import WHERE id = ?", Integer.class, importA)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM team_invitation WHERE id = ? AND status = 'PENDING'", Integer.class, invitationA)).isEqualTo(1);
    }

    @Test
    void everyActorWithoutTheRightIsRefusedExactlyAsDeclaredAndNothingChanges() throws Exception {
        String before = fingerprint();
        List<String> failures = new ArrayList<>();
        int probes = 0;
        for (Ep ep : manifest()) {
            for (Denial denial : denials(ep.rule())) {
                MvcResult r = send(denial.actor(), ep);
                probes++;
                if (r.getResponse().getStatus() != denial.status()) {
                    failures.add(ep.key() + " as " + denial.label() + ": expected " + denial.status() + " but got "
                        + r.getResponse().getStatus() + " " + r.getResponse().getContentAsString());
                }
            }
        }
        assertThat(failures).as("authorization failures").isEmpty();
        assertThat(probes).as("the matrix must actually probe something").isGreaterThan(200);
        assertThat(fingerprint()).as("denied calls must not change any data").isEqualTo(before);
    }

    // ---- plumbing ----

    private MvcResult send(UUID actor, Ep ep) throws Exception {
        String path = ep.path().get();
        org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder<?> req;
        if (ep.multipart()) {
            req = multipart(path).file(new MockMultipartFile("file", "roster.csv", "text/csv",
                "Student ID,Last Name,First Name\nS-X1,Last,First\n".getBytes()));
        } else {
            req = request(HttpMethod.valueOf(ep.method()), path);
        }
        if (!ep.method().equals("GET")) req.with(csrf());
        if (actor != null) req.cookie(sessionOf(actor));
        String body = ep.body().get();
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(req).andReturn();
    }

    private Cookie sessionOf(UUID user) throws Exception {
        Cookie cached = sessions.get(user);
        if (cached != null) return cached;
        MvcResult r = mvc.perform(request(HttpMethod.POST, "/api/v1/auth/login").with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + names.get(user) + "@x.edu\",\"password\":\"" + PASSWORD + "\"}")).andReturn();
        assertThat(r.getResponse().getStatus()).as("login " + names.get(user)).isEqualTo(200);
        Cookie cookie = r.getResponse().getHeaders("Set-Cookie").stream()
            .filter(h -> h.startsWith("SESSION="))
            .map(h -> new Cookie("SESSION", h.substring("SESSION=".length(), h.indexOf(';'))))
            .findFirst().orElseThrow();
        sessions.put(user, cookie);
        return cookie;
    }

    private UUID user(String name, String role, String number) {
        UUID id = jdbc.queryForObject("""
            INSERT INTO app_user (role, status, student_number, last_name, first_name, email, email_verified_at, password_hash)
            VALUES (?, 'ACTIVE', ?, ?, 'T', ?, now(), ?) RETURNING id
            """, UUID.class, role, number, name, name + "@x.edu", encoder.encode(PASSWORD));
        names.put(id, name);
        return id;
    }

    private UUID clazz(String code, UUID instructor) {
        UUID id = jdbc.queryForObject("""
            WITH c AS (INSERT INTO academic_class (academic_year, semester, subject_code, subject_name, section, team_formation, team_min_size, team_max_size, owner_id)
            VALUES ('2025-2026', '1st', ?, 'Subject', 'A', 'PARTICIPANT_FORMED', 2, 4, ?) RETURNING id)
            INSERT INTO class_instructor (class_id, user_id) SELECT id, ? FROM c RETURNING class_id
            """, UUID.class, code, instructor, instructor);
        return id;
    }

    /** Everything a mutating endpoint could touch, as one comparable string. */
    private String fingerprint() {
        List<String> parts = new ArrayList<>();
        parts.add(jdbc.queryForList("SELECT id || ':' || version || ':' || status || ':' || section || ':' || owner_id FROM academic_class ORDER BY id", String.class).toString());
        parts.add(jdbc.queryForList("SELECT id || ':' || version || ':' || status || ':' || title FROM project ORDER BY id", String.class).toString());
        parts.add(jdbc.queryForList("SELECT project_id || ':' || user_id || ':' || role || ':' || (left_at IS NULL) FROM project_member ORDER BY project_id, user_id, role", String.class).toString());
        parts.add(jdbc.queryForList("SELECT class_id || ':' || student_id || ':' || status FROM enrollment ORDER BY class_id, student_id", String.class).toString());
        parts.add(jdbc.queryForList("SELECT class_id || ':' || user_id || ':' || array_to_string(capabilities, ',') FROM class_instructor ORDER BY class_id, user_id", String.class).toString());
        parts.add(jdbc.queryForList("SELECT id || ':' || status FROM team_invitation ORDER BY id", String.class).toString());
        parts.add(jdbc.queryForList("SELECT id || ':' || status FROM roster_import ORDER BY id", String.class).toString());
        parts.add(jdbc.queryForList("SELECT import_id || ':' || row_number || ':' || coalesce(resolution, '-') FROM roster_import_row ORDER BY import_id, row_number", String.class).toString());
        parts.add(jdbc.queryForList("SELECT id || ':' || role || ':' || status || ':' || version FROM app_user ORDER BY id", String.class).toString());
        parts.add(String.valueOf(jdbc.queryForObject("SELECT count(*) FROM temp_credential", Integer.class)));
        parts.add(jdbc.queryForList("SELECT id || ':' || state FROM test_job ORDER BY id", String.class).toString());
        parts.add(String.valueOf(jdbc.queryForObject("SELECT count(*) FROM outbox_event", Integer.class)));
        parts.add(String.valueOf(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action NOT IN ('LOGIN_SUCCEEDED', 'LOGIN_FAILED')", Integer.class)));
        return String.join("\n", parts);
    }
}
