package com.blissfuljuan.dieapi.ops;

import com.blissfuljuan.dieapi.PostgresIntegrationTest;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Public probes show the status word only; the ADMIN report shows per-dependency detail including worker heartbeats. */
class PlatformHealthIntegrationTest extends PostgresIntegrationTest {

    private static final HttpServer WORKERS = startWorkerStub();

    @DynamicPropertySource
    static void workers(DynamicPropertyRegistry r) {
        int port = WORKERS.getAddress().getPort();
        r.add("die.ops.workers.orchestrator-url", () -> "http://127.0.0.1:" + port + "/orchestrator/health/ready");
        r.add("die.ops.workers.document-worker-url", () -> "http://127.0.0.1:" + port + "/document/health/ready");
    }

    @AfterAll
    static void stop() {
        WORKERS.stop(0);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;

    private static HttpServer startWorkerStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            // fresh heartbeat, ready
            server.createContext("/orchestrator/health/ready", ex -> reply(ex, 200,
                "{\"status\":\"UP\",\"worker\":\"orchestrator\",\"secondsSinceHeartbeat\":4}"));
            // answers, but not ready, with a stale heartbeat
            server.createContext("/document/health/ready", ex -> reply(ex, 503,
                "{\"status\":\"DOWN\",\"worker\":\"document-analysis\",\"consuming\":false,\"secondsSinceHeartbeat\":400.5}"));
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @Test
    void publicProbesExposeOnlyTheStatusWord() throws Exception {
        for (String path : new String[] {"/actuator/health/liveness", "/actuator/health/readiness"}) {
            MvcResult r = mvc.perform(get(path)).andReturn();
            assertThat(r.getResponse().getStatus()).as(path).isEqualTo(200);
            assertThat(r.getResponse().getContentAsString()).as(path).isEqualTo("{\"status\":\"UP\"}");
        }
        // the aggregate endpoint adds only the names of the groups
        MvcResult aggregate = mvc.perform(get("/actuator/health")).andReturn();
        assertThat(aggregate.getResponse().getStatus()).isEqualTo(200);
        assertThat(aggregate.getResponse().getContentAsString()).isEqualTo("{\"groups\":[\"liveness\",\"readiness\"],\"status\":\"UP\"}");
        String body = mvc.perform(get("/actuator/health/readiness")).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("jdbc").doesNotContain("PostgreSQL").doesNotContain("components").doesNotContain("details");
    }

    @Test
    void actuatorExposesNothingBeyondHealthAndInfo() throws Exception {
        for (String path : new String[] {"/actuator/env", "/actuator/beans", "/actuator/metrics", "/actuator/heapdump", "/actuator/loggers"}) {
            int status = mvc.perform(get(path)).andReturn().getResponse().getStatus();
            assertThat(status).as(path).isIn(401, 404);
        }
    }

    @Test
    void adminReportNeedsAnAdministrator() throws Exception {
        assertThat(mvc.perform(get("/api/v1/ops/health")).andReturn().getResponse().getStatus()).isEqualTo(401);

        insertUser("COORDINATOR", "coord-health@x.edu");
        Cookie coordinator = login("coord-health@x.edu");
        assertThat(mvc.perform(get("/api/v1/ops/health").cookie(coordinator)).andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void adminReportShowsDependenciesWorkerHeartbeatsAndBacklogWithoutInternals() throws Exception {
        insertUser("ADMIN", "admin-health@x.edu");
        jdbc.update("INSERT INTO test_job (state, correlation_id) VALUES ('QUEUED', 'corr-health-0001')");
        Cookie admin = login("admin-health@x.edu");

        MvcResult r = mvc.perform(get("/api/v1/ops/health").cookie(admin)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getHeader("Cache-Control")).contains("no-store");
        String json = r.getResponse().getContentAsString();
        tools.jackson.databind.JsonNode n = new tools.jackson.databind.ObjectMapper().readTree(json);

        assertThat(n.get("status").asString()).as("a down worker degrades the platform").isEqualTo("DEGRADED");
        assertThat(n.get("database").get("status").asString()).isEqualTo("UP");
        assertThat(n.get("broker").get("status").asString()).as("messaging is disabled in this test context").isEqualTo("DISABLED");

        tools.jackson.databind.JsonNode orchestrator = n.get("workers").get(0);
        assertThat(orchestrator.get("name").asString()).isEqualTo("orchestrator");
        assertThat(orchestrator.get("status").asString()).isEqualTo("UP");
        assertThat(orchestrator.get("heartbeatAgeSeconds").asDouble()).isEqualTo(4.0);
        assertThat(orchestrator.get("heartbeatFresh").asBoolean()).isTrue();

        tools.jackson.databind.JsonNode document = n.get("workers").get(1);
        assertThat(document.get("name").asString()).isEqualTo("document-worker");
        assertThat(document.get("status").asString()).isEqualTo("DOWN");
        assertThat(document.get("heartbeatAgeSeconds").asDouble()).isEqualTo(400.5);
        assertThat(document.get("heartbeatFresh").asBoolean()).isFalse();

        assertThat(n.get("outbox").get("pending").asLong()).isZero();
        assertThat(n.get("testJobs").get("queued").asLong()).isEqualTo(1);
        assertThat(json).doesNotContain("jdbc:").doesNotContain("127.0.0.1").doesNotContain("password");
    }

    private void insertUser(String role, String email) {
        jdbc.update("""
            INSERT INTO app_user (role, status, last_name, first_name, email, email_verified_at, password_hash)
            VALUES (?, 'ACTIVE', 'H', 'T', ?, now(), ?)
            """, role, email, encoder.encode("correct horse battery staple"));
    }

    private Cookie login(String email) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"correct horse battery staple\"}")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return r.getResponse().getHeaders("Set-Cookie").stream().filter(h -> h.startsWith("SESSION="))
            .map(h -> new Cookie("SESSION", h.substring(8, h.indexOf(';')))).findFirst().orElseThrow();
    }
}
