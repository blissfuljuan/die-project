package com.blissfuljuan.die.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.blissfuljuan.die.PostgresIntegrationTest;
import com.blissfuljuan.testsupport.FailingController;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * What clients see when things go wrong: every failure has the same shape (status, readable detail, stable code,
 * timestamp, traceId == X-Request-Id), says what to do about it, and leaks nothing internal.
 */
@Import(FailingController.class)
class GlobalExceptionHandlerIntegrationTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct horse battery staple";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;

    Cookie admin, student;

    @BeforeEach
    void seed() throws Exception {
        user("admin@x.edu", "ADMIN", null);
        user("student@x.edu", "STUDENT", "S-1");
        admin = login("admin@x.edu");
        student = login("student@x.edu");
    }

    private void user(String email, String role, String number) {
        jdbc.update("""
            INSERT INTO app_user (role, status, student_number, last_name, first_name, email, email_verified_at, password_hash)
            VALUES (?, 'ACTIVE', ?, 'L', 'F', ?, now(), ?)
            """, role, number, email, encoder.encode(PASSWORD));
    }

    private Cookie login(String email) throws Exception {
        MvcResult r = mvc.perform(request(HttpMethod.POST, "/api/v1/auth/login").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")).andReturn();
        return r.getResponse().getHeaders("Set-Cookie").stream().filter(h -> h.startsWith("SESSION="))
            .map(h -> new Cookie("SESSION", h.substring("SESSION=".length(), h.indexOf(';')))).findFirst().orElseThrow();
    }

    private MvcResult call(Cookie cookie, HttpMethod method, String path, String contentType, String body, boolean withCsrf) throws Exception {
        MockHttpServletRequestBuilder req = request(method, path);
        if (withCsrf) req.with(csrf());
        if (cookie != null) req.cookie(cookie);
        if (body != null) req.contentType(contentType).content(body);
        return mvc.perform(req).andReturn();
    }

    private MvcResult json(Cookie cookie, HttpMethod method, String path, String body) throws Exception {
        return call(cookie, method, path, MediaType.APPLICATION_JSON_VALUE, body, method != HttpMethod.GET);
    }

    private static String body(MvcResult r) throws Exception { return r.getResponse().getContentAsString(); }

    /** Asserts the common error shape and returns the parsed body. */
    private String assertProblem(MvcResult r, int status, String code) throws Exception {
        String body = body(r);
        assertThat(r.getResponse().getStatus()).as(body).isEqualTo(status);
        assertThat(r.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat((Integer) JsonPath.read(body, "$.status")).isEqualTo(status);
        assertThat((String) JsonPath.read(body, "$.code")).isEqualTo(code);
        assertThat((String) JsonPath.read(body, "$.title")).isNotBlank();
        assertThat((String) JsonPath.read(body, "$.detail")).isNotBlank();
        assertThat((String) JsonPath.read(body, "$.timestamp")).matches("\\d{4}-\\d{2}-\\d{2}T.*Z");
        String traceId = JsonPath.read(body, "$.traceId");
        assertThat(traceId).as("traceId").isNotBlank();
        assertThat(r.getResponse().getHeader("X-Request-Id")).as("X-Request-Id equals traceId").isEqualTo(traceId);
        return body;
    }

    // ---- security failures (raised before any controller) ----

    @Test
    void anAnonymousCallerIsToldToSignIn() throws Exception {
        String body = assertProblem(json(null, HttpMethod.GET, "/api/v1/auth/me", null), 401, "AUTHENTICATION_REQUIRED");
        assertThat((String) JsonPath.read(body, "$.detail")).contains("Sign in");
        assertThat((String) JsonPath.read(body, "$.instance")).isEqualTo("/api/v1/auth/me");
    }

    @Test
    void aMissingCsrfTokenSaysHowToGetOne() throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"a@b.c\",\"password\":\"x\"}")).andReturn();
        String body = assertProblem(r, 403, "CSRF_TOKEN_INVALID");
        assertThat((String) JsonPath.read(body, "$.detail")).contains("/api/v1/auth/csrf").contains("X-XSRF-TOKEN");
    }

    @Test
    void aStudentUsingAnAdminEndpointIsToldTheyLackPermission() throws Exception {
        String body = assertProblem(json(student, HttpMethod.GET, "/api/v1/users", null), 403, "ACCESS_DENIED");
        assertThat((String) JsonPath.read(body, "$.detail")).isEqualTo("You do not have permission to perform this action.");
    }

    // ---- sign-in ----

    @Test
    void wrongPasswordAndUnknownUserGetTheSameMessage_andRepeatedFailuresAreThrottled() throws Exception {
        MvcResult wrong = json(null, HttpMethod.POST, "/api/v1/auth/login", "{\"email\":\"admin@x.edu\",\"password\":\"nope\"}");
        MvcResult unknown = json(null, HttpMethod.POST, "/api/v1/auth/login", "{\"email\":\"ghost@x.edu\",\"password\":\"nope\"}");
        String a = assertProblem(wrong, 401, "INVALID_CREDENTIALS");
        String b = assertProblem(unknown, 401, "INVALID_CREDENTIALS");
        assertThat((String) JsonPath.read(a, "$.detail")).isEqualTo("Invalid email or password").isEqualTo(JsonPath.read(b, "$.detail"));

        for (int i = 0; i < 4; i++) json(null, HttpMethod.POST, "/api/v1/auth/login", "{\"email\":\"admin@x.edu\",\"password\":\"nope\"}");
        String throttled = assertProblem(json(null, HttpMethod.POST, "/api/v1/auth/login",
            "{\"email\":\"admin@x.edu\",\"password\":\"" + PASSWORD + "\"}"), 429, "TOO_MANY_ATTEMPTS");
        assertThat((String) JsonPath.read(throttled, "$.detail")).contains("Wait");
    }

    // ---- bad requests ----

    @Test
    void invalidFieldsAreListedWithoutEchoingTheirValues() throws Exception {
        String body = assertProblem(json(null, HttpMethod.POST, "/api/v1/auth/login",
            "{\"email\":\"\",\"password\":\"super-secret-value\"}"), 400, "VALIDATION_FAILED");
        List<String> fields = JsonPath.read(body, "$.errors[*].field");
        assertThat(fields).contains("email");
        assertThat((List<String>) JsonPath.read(body, "$.errors[?(@.field=='email')].message")).isNotEmpty();
        assertThat(body).doesNotContain("super-secret-value");
        assertThat((String) JsonPath.read(body, "$.detail")).contains("errors");
    }

    @Test
    void malformedAndWronglyTypedJsonGetSpecificMessages() throws Exception {
        String broken = assertProblem(json(null, HttpMethod.POST, "/api/v1/auth/login", "{not json"), 400, "MALFORMED_REQUEST");
        assertThat((String) JsonPath.read(broken, "$.detail")).contains("not valid JSON");

        String wrongType = assertProblem(json(admin, HttpMethod.POST, "/api/v1/classes", "{\"instructorIds\":\"not-a-list\"}"), 400, "MALFORMED_REQUEST");
        assertThat((String) JsonPath.read(wrongType, "$.detail")).contains("instructorIds");
    }

    @Test
    void unsupportedContentTypeAndMethodExplainWhatIsSupported() throws Exception {
        String type = assertProblem(call(null, HttpMethod.POST, "/api/v1/auth/login", "text/plain", "x", true), 415, "UNSUPPORTED_MEDIA_TYPE");
        assertThat((String) JsonPath.read(type, "$.detail")).contains("text/plain").contains("application/json");

        MvcResult notAllowed = json(admin, HttpMethod.GET, "/api/v1/auth/login", null);
        String method = assertProblem(notAllowed, 405, "METHOD_NOT_ALLOWED");
        assertThat((String) JsonPath.read(method, "$.detail")).contains("GET").contains("POST");
        assertThat(notAllowed.getResponse().getHeader("Allow")).contains("POST");
    }

    @Test
    void aBadIdInThePathNamesTheParameter_andAnUnknownEndpointIs404() throws Exception {
        String type = assertProblem(json(admin, HttpMethod.GET, "/api/v1/classes/not-a-uuid", null), 400, "INVALID_PARAMETER");
        assertThat((String) JsonPath.read(type, "$.detail")).isEqualTo("Parameter 'id' must be a valid UUID.");

        String missing = assertProblem(json(admin, HttpMethod.GET, "/api/v1/nothing-here", null), 404, "ENDPOINT_NOT_FOUND");
        assertThat((String) JsonPath.read(missing, "$.detail")).contains("GET").contains("nothing-here");
    }

    @Test
    void aMissingUploadFileIsExplained() throws Exception {
        MvcResult r = mvc.perform(multipart("/api/v1/classes/" + UUID.randomUUID() + "/roster-imports").with(csrf()).cookie(admin)).andReturn();
        String body = assertProblem(r, 400, "MISSING_PART");
        assertThat((String) JsonPath.read(body, "$.detail")).contains("'file'");
    }

    // ---- business failures ----

    @Test
    void notFoundAndConflictCarryStableCodes() throws Exception {
        assertProblem(json(admin, HttpMethod.GET, "/api/v1/classes/" + UUID.randomUUID(), null), 404, "CLASS_NOT_FOUND");

        String user = "{\"role\":\"INSTRUCTOR\",\"lastName\":\"A\",\"firstName\":\"B\",\"email\":\"dup@x.edu\"}";
        assertThat(json(admin, HttpMethod.POST, "/api/v1/users", user).getResponse().getStatus()).isEqualTo(201);
        String dup = assertProblem(json(admin, HttpMethod.POST, "/api/v1/users", user), 409, "ALREADY_EXISTS");
        assertThat((String) JsonPath.read(dup, "$.detail")).contains("already exists");

        String custom = assertProblem(json(admin, HttpMethod.GET, "/api/v1/test/api-error", null), 409, "CUSTOM_CODE");
        assertThat((String) JsonPath.read(custom, "$.detail")).isEqualTo("A custom, person-readable message.");
    }

    // ---- unexpected failures leak nothing ----

    @Test
    void anUnexpectedFailureGivesAGenericMessageAndAReferenceNeverTheInternals() throws Exception {
        MvcResult r = json(admin, HttpMethod.GET, "/api/v1/test/boom", null);
        String body = assertProblem(r, 500, "INTERNAL_ERROR");
        assertThat(body).doesNotContain("secret internal detail").doesNotContain("hunter2").doesNotContain("10.9.8.7")
            .doesNotContain("IllegalState").doesNotContain("jdbc:").doesNotContain("at com.");
        String traceId = JsonPath.read(body, "$.traceId");
        assertThat((String) JsonPath.read(body, "$.detail")).contains("Something went wrong on our side").contains(traceId);
    }

    @Test
    void infrastructureProblemsAreReportedAsTemporaryNotAsInternalDetail() throws Exception {
        String db = assertProblem(json(admin, HttpMethod.GET, "/api/v1/test/db-down", null), 503, "SERVICE_UNAVAILABLE");
        assertThat(db).doesNotContain("10.0.0.5").doesNotContain("5432");
        assertThat((String) JsonPath.read(db, "$.detail")).contains("Try again");

        String large = assertProblem(json(admin, HttpMethod.GET, "/api/v1/test/too-large", null), 413, "PAYLOAD_TOO_LARGE");
        assertThat((String) JsonPath.read(large, "$.detail")).contains("too large").contains("5 MB");
    }

    // ---- correlation id ----

    @Test
    void aWellFormedRequestIdIsKeptAndAnythingElseIsReplaced() throws Exception {
        MvcResult kept = mvc.perform(get("/api/v1/auth/me").header("X-Request-Id", "client-req-12345")).andReturn();
        assertThat(kept.getResponse().getHeader("X-Request-Id")).isEqualTo("client-req-12345");
        assertThat((String) JsonPath.read(body(kept), "$.traceId")).isEqualTo("client-req-12345");

        for (String bad : new String[] {"short", "has spaces in it 1234", "x".repeat(200), "new\\nline-injection-1"}) {
            MvcResult replaced = mvc.perform(get("/api/v1/auth/me").header("X-Request-Id", bad)).andReturn();
            String id = replaced.getResponse().getHeader("X-Request-Id");
            assertThat(id).as("replacement for '%s'", bad).isNotEqualTo(bad).matches("[0-9a-f-]{36}");
        }
    }

    // ---- container-level errors ----

    @Test
    void errorsRedispatchedByTheContainerUseTheSameFormat() throws Exception {
        MvcResult r = mvc.perform(get("/error").cookie(admin).requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 400)
            .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/some/where")).andReturn();
        String body = body(r);
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat((String) JsonPath.read(body, "$.code")).isEqualTo("BAD_REQUEST");
        assertThat((String) JsonPath.read(body, "$.instance")).isEqualTo("/some/where");
    }
}
