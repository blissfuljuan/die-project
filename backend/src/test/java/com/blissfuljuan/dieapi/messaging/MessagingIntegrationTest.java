package com.blissfuljuan.dieapi.messaging;

import com.blissfuljuan.dieapi.PostgresIntegrationTest;
import com.blissfuljuan.dieapi.messaging.model.Envelope;
import com.blissfuljuan.dieapi.messaging.service.EnvelopeCodec;
import com.blissfuljuan.dieapi.messaging.service.EnvelopeSchemaValidator;
import com.blissfuljuan.dieapi.messaging.service.OutboxRelay;
import com.blissfuljuan.dieapi.ops.repository.TestJobRepository;
import com.blissfuljuan.dieapi.ops.service.TestJobService;
import com.blissfuljuan.dieapi.ops.view.TestJobView;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Real Postgres + real RabbitMQ (topology loaded from infra/rabbitmq/definitions.json). The test plays the workers:
 * it consumes die.test.jobs and publishes test.progress / test.result like the orchestrator would.
 * Covers: outbox -> publisher confirm -> broker -> result consumer -> test_job state, duplicate results, broker outage
 * with recovery (publisher and consumer reconnect), retry tier and recovery, retry exhaustion -> DLQ, poison -> DLQ.
 */
@TestPropertySource(properties = {
    "die.messaging.enabled=true",
    "die.messaging.relay.enabled=false",          // the tests call OutboxRelay.relayOnce() themselves
    "die.messaging.relay.max-backoff-seconds=1",
    "die.messaging.confirm-timeout-ms=3000"
})
class MessagingIntegrationTest extends PostgresIntegrationTest {

    private static final int BROKER_PORT = freePort();
    private static final GenericContainer<?> RABBIT = new GenericContainer<>("rabbitmq:3.13-management-alpine")
        .withEnv("RABBITMQ_DEFAULT_USER", "die").withEnv("RABBITMQ_DEFAULT_PASS", "die-test-password")
        .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
            .withPortBindings(new PortBinding(Ports.Binding.bindPort(BROKER_PORT), new ExposedPort(5672))))
        .withCopyFileToContainer(MountableFile.forHostPath(infra("rabbitmq/definitions.json")), "/etc/rabbitmq/definitions.json")
        .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1));

    private static final String ADMIN_PASSWORD = "correct horse battery staple";
    private static final List<String> TEST_QUEUES = queues();

    @DynamicPropertySource
    static void broker(DynamicPropertyRegistry r) {
        synchronized (RABBIT) {
            if (!RABBIT.isRunning()) {
                RABBIT.start();
                try {
                    RABBIT.execInContainer("rabbitmqctl", "import_definitions", "/etc/rabbitmq/definitions.json");
                    awaitTopology();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        r.add("die.messaging.host", RABBIT::getHost);
        r.add("die.messaging.port", () -> BROKER_PORT);
        r.add("die.messaging.username", () -> "die");
        r.add("die.messaging.password", () -> "die-test-password");
    }

    @Autowired TestJobService jobs;
    @Autowired OutboxRelay relay;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired EnvelopeCodec codec;
    @Autowired EnvelopeSchemaValidator schema;
    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder encoder;
    @MockitoSpyBean TestJobRepository jobRepository;

    private Connection workerConnection;
    private Channel worker;
    private UUID adminId;

    @BeforeEach
    void connectAsWorker() throws Exception {
        ensureBrokerIsUp();
        workerConnection = connect();
        worker = workerConnection.createChannel();
        for (String q : TEST_QUEUES) {
            worker.queuePurge(q);
        }
        awaitTrue("the API consumer subscribes to die.test.results", 20_000,
            () -> worker.queueDeclarePassive("die.test.results").getConsumerCount() >= 1);
        adminId = jdbc.queryForObject("""
            INSERT INTO app_user (role, status, last_name, first_name, email, email_verified_at, password_hash)
            VALUES ('ADMIN', 'ACTIVE', 'Admin', 'T', 'ops-admin@x.edu', now(), ?) RETURNING id
            """, UUID.class, encoder.encode(ADMIN_PASSWORD));
    }

    @AfterEach
    void closeWorker() throws Exception {
        reset(jobRepository);
        if (workerConnection != null && workerConnection.isOpen()) {
            workerConnection.close();
        }
    }

    // ---- the happy path ----

    @Test
    void submitRelayWorkerResultAndTheJobSucceeds() throws Exception {
        TestJobView job = jobs.submit(adminId, "hello", false, "corr-happy-0001");
        assertThat(job.state()).isEqualTo("QUEUED");
        assertThat(outboxStatus(job.id())).isEqualTo("PENDING");

        assertThat(relay.relayOnce()).isEqualTo(1);
        assertThat(outboxStatus(job.id())).isEqualTo("PUBLISHED");

        GetResponse delivered = awaitGet("die.test.jobs");
        assertThat(delivered.getProps().getDeliveryMode()).as("persistent").isEqualTo(2);
        assertThat(schema.validate(new String(delivered.getBody(), StandardCharsets.UTF_8))).isEmpty();
        Envelope ping = codec.decode(delivered.getBody());
        assertThat(ping.type()).isEqualTo("test.ping");
        assertThat(ping.correlationId()).isEqualTo("corr-happy-0001");
        assertThat(ping.attempt()).isEqualTo(1);
        assertThat(ping.payload()).containsEntry("testJobId", job.id().toString()).containsEntry("note", "hello");
        worker.basicAck(delivered.getEnvelope().getDeliveryTag(), false);

        publishFromWorker("test.progress", UUID.randomUUID(), "corr-happy-0001", 1,
            Map.of("testJobId", job.id().toString(), "stage", "orchestrator-started", "worker", "orchestrator"));
        awaitState(job.id(), "RUNNING");

        publishFromWorker("test.result", UUID.randomUUID(), "corr-happy-0001", 1, successPayload(job.id()));
        awaitState(job.id(), "SUCCEEDED");

        TestJobView finished = jobs.get(job.id());
        assertThat(finished.result()).containsEntry("worker", "document-analysis");
        assertThat(finished.finishedAt()).isNotNull();
        assertThat(count("audit_log WHERE action = 'TEST_JOB_SUBMITTED'")).isEqualTo(1);
    }

    @Test
    void theAdminEndpointAcceptsTheJobAndCarriesTheRequestIdThroughTheOutbox() throws Exception {
        Cookie session = login();
        MvcResult accepted = mvc.perform(post("/api/v1/ops/test-jobs").with(csrf()).cookie(session)
                .header("X-Request-Id", "it-corr-0001")
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"via http\"}"))
            .andReturn();
        assertThat(accepted.getResponse().getStatus()).isEqualTo(202);
        String body = accepted.getResponse().getContentAsString();
        UUID id = UUID.fromString(mapper.readTree(body).get("id").asString());
        assertThat(accepted.getResponse().getHeader("Location")).endsWith("/api/v1/ops/test-jobs/" + id);
        assertThat(body).contains("\"state\":\"QUEUED\"");

        assertThat(jdbc.queryForObject("SELECT correlation_id FROM outbox_event WHERE aggregate_id = ?", String.class,
            id.toString())).isEqualTo("it-corr-0001");

        MvcResult read = mvc.perform(get("/api/v1/ops/test-jobs/" + id).cookie(session)).andReturn();
        assertThat(read.getResponse().getStatus()).isEqualTo(200);
        MvcResult missing = mvc.perform(get("/api/v1/ops/test-jobs/" + UUID.randomUUID()).cookie(session)).andReturn();
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(missing.getResponse().getContentAsString()).contains("TEST_JOB_NOT_FOUND");
    }

    // ---- platform health and logging with a real broker ----

    @Test
    void readinessAndTheAdminReportTrackTheBrokerAndReportQueueDepths() throws Exception {
        Cookie admin = login();
        assertThat(mvc.perform(get("/actuator/health/readiness")).andReturn().getResponse().getContentAsString())
            .isEqualTo("{\"status\":\"UP\"}");
        assertThat(mvc.perform(get("/actuator/health/liveness")).andReturn().getResponse().getStatus()).isEqualTo(200);

        TestJobView job = jobs.submit(adminId, "depth", false, "corr-health-depth");
        relay.relayOnce();
        awaitTrue("queue depth shows the published job", 10_000, () -> {
            String json = mvc.perform(get("/api/v1/ops/health").cookie(admin)).andReturn().getResponse().getContentAsString();
            tools.jackson.databind.JsonNode n = mapper.readTree(json);
            return "UP".equals(n.get("broker").get("status").asString())
                && n.get("queueDepths").get("die.test.jobs").asLong() == 1
                && n.get("outbox").get("pending").asLong() == 0;
        });
        assertThat(job.id()).isNotNull();

        stopBroker();
        try {
            awaitTrue("readiness turns DOWN while the broker is stopped", 30_000,
                () -> mvc.perform(get("/actuator/health/readiness")).andReturn().getResponse().getStatus() == 503);
            assertThat(mvc.perform(get("/actuator/health/liveness")).andReturn().getResponse().getStatus())
                .as("liveness never depends on the broker").isEqualTo(200);
            tools.jackson.databind.JsonNode n = mapper.readTree(mvc.perform(get("/api/v1/ops/health").cookie(admin))
                .andReturn().getResponse().getContentAsString());
            assertThat(n.get("status").asString()).isEqualTo("DEGRADED");
            assertThat(n.get("broker").get("status").asString()).isEqualTo("DOWN");
            assertThat(n.get("broker").get("reason").asString()).doesNotContain(RABBIT.getHost());
        } finally {
            startBroker();
        }
        awaitTrue("readiness recovers when the broker returns", 60_000,
            () -> mvc.perform(get("/actuator/health/readiness")).andReturn().getResponse().getStatus() == 200);
    }

    @Test
    void secretsNeverReachTheLogsAndTheRequestIdTravelsThroughTheOutboxIntoWorkerMessages() throws Exception {
        try (com.blissfuljuan.dieapi.support.LogCapture logs = new com.blissfuljuan.dieapi.support.LogCapture(
            "com.blissfuljuan", "org.springframework.security", "org.springframework.web")) {
            Cookie session = login();
            MvcResult accepted = mvc.perform(post("/api/v1/ops/test-jobs").with(csrf()).cookie(session)
                    .header("X-Request-Id", "log-corr-0001").header("Authorization", "Bearer leaked-bearer-value-123")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"log check\"}"))
                .andReturn();
            UUID id = UUID.fromString(mapper.readTree(accepted.getResponse().getContentAsString()).get("id").asString());
            assertThat(relay.relayOnce()).isEqualTo(1);
            GetResponse delivered = awaitGet("die.test.jobs");
            assertThat(codec.decode(delivered.getBody()).correlationId()).isEqualTo("log-corr-0001");
            worker.basicAck(delivered.getEnvelope().getDeliveryTag(), false);
            publishFromWorker("test.result", UUID.randomUUID(), "log-corr-0001", 1, successPayload(id));
            awaitState(id, "SUCCEEDED");

            String text = logs.text();
            assertThat(text).contains("log-corr-0001");
            assertThat(text)
                .doesNotContain("die-test-password")
                .doesNotContain(ADMIN_PASSWORD)
                .doesNotContain("leaked-bearer-value-123")
                .doesNotContain(session.getValue());
        }
    }

    // ---- duplicates ----

    @Test
    void duplicateAndContradictingResultsChangeNothing() throws Exception {
        TestJobView job = jobs.submit(adminId, null, false, "corr-dup-00001");
        TestJobView sentinelJob = jobs.submit(adminId, null, false, "corr-dup-00002");
        UUID resultId = UUID.randomUUID();

        publishFromWorker("test.result", resultId, "corr-dup-00001", 1, successPayload(job.id()));
        awaitState(job.id(), "SUCCEEDED");
        Instant updatedAt = jdbc.queryForObject("SELECT updated_at FROM test_job WHERE id = ?", java.sql.Timestamp.class, job.id()).toInstant();

        // the very same message delivered again (broker redelivery / relay republication)
        publishFromWorker("test.result", resultId, "corr-dup-00001", 1, successPayload(job.id()));
        // a different message that contradicts the finished job
        Map<String, Object> failed = new HashMap<>(successPayload(job.id()));
        failed.put("outcome", "FAILED");
        failed.put("reason", "late contradicting result");
        publishFromWorker("test.result", UUID.randomUUID(), "corr-dup-00001", 1, failed);
        // consumer processes in order: once the sentinel is through, the duplicates were handled too
        publishFromWorker("test.progress", UUID.randomUUID(), "corr-dup-00002", 1,
            Map.of("testJobId", sentinelJob.id().toString(), "stage", "s", "worker", "w"));
        awaitState(sentinelJob.id(), "RUNNING");

        assertThat(jobs.get(job.id()).state()).isEqualTo("SUCCEEDED");
        assertThat(jobs.get(job.id()).failureReason()).isNull();
        assertThat(jdbc.queryForObject("SELECT updated_at FROM test_job WHERE id = ?", java.sql.Timestamp.class, job.id()).toInstant())
            .isEqualTo(updatedAt);
        assertThat(count("inbox_message WHERE message_id = '" + resultId + "'")).isEqualTo(1);
        assertThat(messages("die.test.results.dlq")).isZero();
    }

    // ---- failures ----

    @Test
    void poisonMessageGoesToTheDeadLetterQueueAndTheConsumerKeepsWorking() throws Exception {
        byte[] poison = "this is not json".getBytes(StandardCharsets.UTF_8);
        worker.basicPublish("die.test", "die.test.results", persistent(null, null), poison);
        awaitTrue("poison reaches the DLQ", 10_000, () -> messages("die.test.results.dlq") == 1);

        GetResponse dead = worker.basicGet("die.test.results.dlq", true);
        assertThat(dead.getBody()).isEqualTo(poison);
        assertThat(dead.getProps().getHeaders()).containsKey("x-die-failure-reason");
        assertThat(String.valueOf(dead.getProps().getHeaders().get("x-die-source-queue"))).isEqualTo("die.test.results");
        assertThat(messages("die.test.results")).isZero();

        TestJobView job = jobs.submit(adminId, null, false, "corr-after-poison");
        publishFromWorker("test.result", UUID.randomUUID(), "corr-after-poison", 1, successPayload(job.id()));
        awaitState(job.id(), "SUCCEEDED");
    }

    @Test
    void resultForAnUnknownJobIsDeadLetteredWithoutRetries() throws Exception {
        publishFromWorker("test.result", UUID.randomUUID(), "corr-unknown-job", 1, successPayload(UUID.randomUUID()));
        awaitTrue("unknown job reaches the DLQ", 10_000, () -> messages("die.test.results.dlq") == 1);
        assertThat(messages("die.test.results.retry.1")).isZero();
    }

    @Test
    void aFailingHandlerIsRetriedWithBackoffAndRecoversWhenTheCauseIsGone() throws Exception {
        TestJobView job = jobs.submit(adminId, null, false, "corr-retry-0001");
        doThrow(new IllegalStateException("simulated outage")).when(jobRepository).finish(any(), any(), any(), any(), any());

        UUID messageId = UUID.randomUUID();
        publishFromWorker("test.result", messageId, "corr-retry-0001", 1, successPayload(job.id()));
        awaitTrue("message waits in retry tier 1", 4_000, () -> messages("die.test.results.retry.1") == 1);
        assertThat(jobs.get(job.id()).state()).isEqualTo("QUEUED");
        assertThat(count("inbox_message WHERE message_id = '" + messageId + "'")).as("failed attempt left no inbox row").isZero();

        reset(jobRepository);     // the outage is over; the TTL (5 s) returns the message to the work queue
        awaitState(job.id(), "SUCCEEDED", 30_000);
        assertThat(messages("die.test.results.dlq")).isZero();
    }

    @Test
    void retriesAreBoundedAndTheLastFailureIsDeadLettered() throws Exception {
        TestJobView job = jobs.submit(adminId, null, false, "corr-exhaust-001");
        doThrow(new IllegalStateException("simulated outage")).when(jobRepository).finish(any(), any(), any(), any(), any());

        publishFromWorker("test.result", UUID.randomUUID(), "corr-exhaust-001", 3, successPayload(job.id()));   // third and last attempt
        awaitTrue("exhausted message reaches the DLQ", 10_000, () -> messages("die.test.results.dlq") == 1);
        assertThat(messages("die.test.results.retry.1") + messages("die.test.results.retry.2")).isZero();
        assertThat(jobs.get(job.id()).state()).isEqualTo("QUEUED");
        GetResponse dead = worker.basicGet("die.test.results.dlq", true);
        assertThat(String.valueOf(dead.getProps().getHeaders().get("x-die-failure-reason"))).contains("retries exhausted");
    }

    // ---- broker outage ----

    @Test
    void relayKeepsTheRowPendingWhileTheBrokerIsDownAndBothSidesReconnectWhenItReturns() throws Exception {
        TestJobView job = jobs.submit(adminId, "survives outage", false, "corr-outage-001");
        stopBroker();
        try {
            assertThat(relay.relayOnce()).isZero();
            assertThat(outboxStatus(job.id())).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT attempts FROM outbox_event WHERE aggregate_id = ?", Integer.class, job.id().toString()))
                .isGreaterThanOrEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT last_error FROM outbox_event WHERE aggregate_id = ?", String.class, job.id().toString()))
                .contains("unavailable");
            assertThat(jobs.get(job.id()).state()).as("accepted job is not lost").isEqualTo("QUEUED");
        } finally {
            startBroker();
        }

        awaitTrue("outbox row is published after the broker returns", 60_000, () -> {
            relay.relayOnce();
            return "PUBLISHED".equals(outboxStatus(job.id()));
        });
        reconnectWorker();
        GetResponse delivered = awaitGet("die.test.jobs");
        assertThat(codec.decode(delivered.getBody()).payload()).containsEntry("testJobId", job.id().toString());
        worker.basicAck(delivered.getEnvelope().getDeliveryTag(), false);

        // the API's consumer reconnected on its own: a result published now is processed
        awaitTrue("API consumer resubscribed after the outage", 30_000,
            () -> worker.queueDeclarePassive("die.test.results").getConsumerCount() >= 1);
        publishFromWorker("test.result", UUID.randomUUID(), "corr-outage-001", 1, successPayload(job.id()));
        awaitState(job.id(), "SUCCEEDED", 30_000);
    }

    // ---- helpers ----

    private void reconnectWorker() throws Exception {
        try {
            workerConnection.close();
        } catch (Exception ignored) {
            // the broker restart already killed it
        }
        workerConnection = connect();
        worker = workerConnection.createChannel();
    }

    private Map<String, Object> successPayload(UUID jobId) {
        Map<String, Object> p = new HashMap<>();
        p.put("testJobId", jobId.toString());
        p.put("outcome", "SUCCEEDED");
        p.put("worker", "document-analysis");
        p.put("workers", List.of("orchestrator", "document-analysis"));
        p.put("result", Map.of("pong", true, "noteLength", 5));
        return p;
    }

    private void publishFromWorker(String type, UUID messageId, String correlationId, int attempt,
                                   Map<String, Object> payload) throws Exception {
        Envelope e = new Envelope(1, messageId, correlationId, type, Instant.now().truncatedTo(ChronoUnit.MILLIS), attempt, payload);
        worker.basicPublish("die.test", "die.test.results", persistent(messageId, type),
            codec.encode(e).getBytes(StandardCharsets.UTF_8));
    }

    private static AMQP.BasicProperties persistent(UUID messageId, String type) {
        AMQP.BasicProperties.Builder b = new AMQP.BasicProperties.Builder().deliveryMode(2).contentType("application/json");
        if (messageId != null) b.messageId(messageId.toString());
        if (type != null) b.type(type);
        return b.build();
    }

    private String outboxStatus(UUID jobId) {
        return jdbc.queryForObject("SELECT status FROM outbox_event WHERE aggregate_id = ?", String.class, jobId.toString());
    }

    private int count(String tableAndWhere) {
        return jdbc.queryForObject("SELECT count(*) FROM " + tableAndWhere, Integer.class);
    }

    private int messages(String queue) {
        try {
            return worker.queueDeclarePassive(queue).getMessageCount();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void awaitState(UUID id, String state) throws Exception {
        awaitState(id, state, 15_000);
    }

    private void awaitState(UUID id, String state, long timeoutMs) throws Exception {
        awaitTrue("job " + id + " reaches " + state, timeoutMs, () -> state.equals(jobs.get(id).state()));
    }

    private GetResponse awaitGet(String queue) throws Exception {
        long end = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < end) {
            GetResponse r = worker.basicGet(queue, false);
            if (r != null) return r;
            Thread.sleep(100);
        }
        throw new AssertionError("no message arrived on " + queue);
    }

    private static void awaitTrue(String what, long timeoutMs, BooleanSupplierWithException condition) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        Exception last = null;
        while (System.currentTimeMillis() < end) {
            try {
                if (condition.getAsBoolean()) return;
            } catch (Exception e) {
                last = e;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Timed out waiting for: " + what + (last == null ? "" : " (last error: " + last + ")"));
    }

    @FunctionalInterface
    interface BooleanSupplierWithException {
        boolean getAsBoolean() throws Exception;
    }

    private Cookie login() throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"ops-admin@x.edu\",\"password\":\"" + ADMIN_PASSWORD + "\"}")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return r.getResponse().getHeaders("Set-Cookie").stream()
            .filter(h -> h.startsWith("SESSION="))
            .map(h -> new Cookie("SESSION", h.substring("SESSION=".length(), h.indexOf(';'))))
            .findFirst().orElseThrow();
    }

    private static Connection connect() throws Exception {
        ConnectionFactory f = new ConnectionFactory();
        f.setHost(RABBIT.getHost());
        f.setPort(BROKER_PORT);
        f.setUsername("die");
        f.setPassword("die-test-password");
        f.setConnectionTimeout(3_000);
        return f.newConnection("test-worker");
    }

    private static void ensureBrokerIsUp() throws Exception {
        if (!Boolean.TRUE.equals(RABBIT.getDockerClient().inspectContainerCmd(RABBIT.getContainerId()).exec().getState().getRunning())) {
            startBroker();
        }
    }

    private static void stopBroker() {
        RABBIT.getDockerClient().stopContainerCmd(RABBIT.getContainerId()).withTimeout(5).exec();
    }

    private static void startBroker() throws Exception {
        RABBIT.getDockerClient().startContainerCmd(RABBIT.getContainerId()).exec();
        awaitTopology();
    }

    /** The management plugin imports definitions.json shortly after the broker reports startup. */
    private static void awaitTopology() throws Exception {
        awaitTrue("broker accepts connections and has the topology", 90_000, () -> {
            try (Connection c = connect(); Channel ch = c.createChannel()) {
                for (String q : TEST_QUEUES) ch.queueDeclarePassive(q);
                return true;
            }
        });
    }

    private static List<String> queues() {
        java.util.ArrayList<String> all = new java.util.ArrayList<>();
        for (String q : List.of("die.test.jobs", "die.test.analyze", "die.test.analyze.results", "die.test.results")) {
            all.add(q);
            all.add(q + ".retry.1");
            all.add(q + ".retry.2");
            all.add(q + ".dlq");
        }
        return all;
    }

    private static Path infra(String relative) {
        return Path.of(System.getProperty("die.infra.dir", "../infra")).resolve(relative).toAbsolutePath().normalize();
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
