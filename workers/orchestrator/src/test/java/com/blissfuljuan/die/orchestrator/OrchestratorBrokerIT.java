package com.blissfuljuan.die.orchestrator;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The orchestrator against a real RabbitMQ with the project topology (infra/rabbitmq/definitions.json). The test plays
 * the API (publishes test.ping, reads test.progress / test.result) and the Python worker (reads test.analyze, publishes
 * test.analyze.result). Covers the round trip, duplicate delivery, interruption mid-job, retry exhaustion to the DLQ,
 * poison messages and reconnect after a broker restart.
 */
class OrchestratorBrokerIT {

    private static final String USER = "die";
    private static final String PASSWORD = "die-test-password";
    private static final int PORT = freePort();
    private static final String JOB = "3f2b8c1e-5d4a-4e7b-9a60-1c2d3e4f5a6b";
    private static final List<String> QUEUES = queues();

    private static GenericContainer<?> rabbit;

    private final EnvelopeCodec codec = new EnvelopeCodec();
    private Connection testConnection;
    private Channel test;
    private final List<AutoCloseable> toClose = new ArrayList<>();

    @BeforeAll
    static void startBroker() throws Exception {
        rabbit = new GenericContainer<>("rabbitmq:3.13-management-alpine")
            .withEnv("RABBITMQ_DEFAULT_USER", USER).withEnv("RABBITMQ_DEFAULT_PASS", PASSWORD)
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                .withPortBindings(new PortBinding(Ports.Binding.bindPort(PORT), new ExposedPort(5672))))
            .withCopyFileToContainer(MountableFile.forHostPath(infra("rabbitmq/definitions.json")), "/etc/rabbitmq/definitions.json")
            .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1));
        rabbit.start();
        rabbit.execInContainer("rabbitmqctl", "import_definitions", "/etc/rabbitmq/definitions.json");
        awaitTopology();
    }

    @AfterAll
    static void stopBroker() {
        rabbit.stop();
    }

    @BeforeEach
    void connect() throws Exception {
        ensureBrokerIsUp();
        testConnection = rawConnection();
        test = testConnection.createChannel();
        for (String q : QUEUES) test.queuePurge(q);
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (AutoCloseable c : toClose) c.close();
        toClose.clear();
        if (testConnection.isOpen()) testConnection.close();
    }

    // ---- the worker under test ----

    private Settings settings() {
        return new Settings(rabbit.getHost(), PORT, USER, PASSWORD, "/", 0, 5);
    }

    /** Starts the real worker wiring (same classes as Main) with the given handler for the jobs queue. */
    private QueueWorker startJobsWorker(Handler handler) {
        BrokerConnection connection = new BrokerConnection(settings());
        ConfirmingPublisher publisher = new ConfirmingPublisher(connection, 3_000);
        QueueWorker worker = new QueueWorker(Topology.JOBS, handler, codec, publisher, connection, new ProcessedMessages(100), 5);
        worker.start();
        toClose.add(worker);
        toClose.add(publisher);
        toClose.add(connection);
        return worker;
    }

    private QueueWorker startResultsWorker() {
        BrokerConnection connection = new BrokerConnection(settings());
        ConfirmingPublisher publisher = new ConfirmingPublisher(connection, 3_000);
        QueueWorker worker = new QueueWorker(Topology.ANALYZE_RESULTS, new TestJobHandlers.AnalyzeResult(), codec, publisher,
            connection, new ProcessedMessages(100), 5);
        worker.start();
        toClose.add(worker);
        toClose.add(publisher);
        toClose.add(connection);
        return worker;
    }

    // ---- tests ----

    @Test
    void aPingTravelsThroughTheOrchestratorAndTheResultComesBack() throws Exception {
        QueueWorker jobs = startJobsWorker(new TestJobHandlers.Ping());
        QueueWorker results = startResultsWorker();
        awaitTrue("both consumers subscribe", 15_000, () -> jobs.isConsuming() && results.isConsuming());

        publishPing(ping("a1b2c3d4-0000-4000-8000-000000000001", 1));

        // the API gets test.progress, the document worker gets test.analyze (persistent, schema-valid, same correlation id)
        Envelope progress = awaitEnvelope("die.test.results");
        assertThat(progress.type()).isEqualTo("test.progress");
        assertThat(progress.correlationId()).isEqualTo("req-20261018-0001");
        Envelope analyze = awaitEnvelope("die.test.analyze");
        assertThat(analyze.type()).isEqualTo("test.analyze");
        assertThat(analyze.payload()).containsEntry("testJobId", JOB).containsEntry("note", "hello");

        // the document worker answers
        publishAnalyzeResult(analyze, "SUCCEEDED");
        Envelope result = awaitEnvelope("die.test.results");
        assertThat(result.type()).isEqualTo("test.result");
        assertThat(result.correlationId()).isEqualTo("req-20261018-0001");
        assertThat(result.payload()).containsEntry("outcome", "SUCCEEDED").containsEntry("testJobId", JOB);
        assertThat(result.payload().get("workers")).isEqualTo(List.of("orchestrator", "document-analysis"));
    }

    @Test
    void aDuplicatePingDoesNotForwardTwice() throws Exception {
        QueueWorker jobs = startJobsWorker(new TestJobHandlers.Ping());
        awaitTrue("consumer subscribes", 15_000, jobs::isConsuming);

        byte[] body = ping("a1b2c3d4-0000-4000-8000-000000000001", 1);
        publishPing(body);
        awaitTrue("first delivery handled", 10_000, () -> jobs.handledCount() == 1);
        publishPing(body);
        publishPing(ping("a1b2c3d4-0000-4000-8000-000000000002", 1));      // sentinel, processed after the duplicate
        awaitTrue("sentinel handled", 10_000, () -> jobs.handledCount() == 2);

        assertThat(messages("die.test.analyze")).as("one forward per distinct ping").isEqualTo(2);
        assertThat(messages("die.test.results")).isEqualTo(2);
        assertThat(messages("die.test.jobs.dlq")).isZero();
    }

    @Test
    void aJobTakenByAWorkerThatDiesIsRedeliveredAndHandledByTheNext() throws Exception {
        publishPing(ping("a1b2c3d4-0000-4000-8000-000000000001", 1));

        // worker 1 receives the job and dies before acknowledging
        Connection doomed = rawConnection();
        GetResponse taken = doomed.createChannel().basicGet("die.test.jobs", false);
        assertThat(taken).isNotNull();
        doomed.abort();

        QueueWorker survivor = startJobsWorker(new TestJobHandlers.Ping());
        awaitTrue("redelivered job handled", 15_000, () -> survivor.handledCount() == 1);
        assertThat(messages("die.test.analyze")).isEqualTo(1);
        assertThat(messages("die.test.jobs")).isZero();
    }

    @Test
    void aFailingHandlerIsRetriedThenTheJobIsReportedFailedAndDeadLettered() throws Exception {
        Handler failing = new Handler() {
            public List<Outgoing> handle(Envelope in) { throw new IllegalStateException("simulated failure"); }
            public List<Outgoing> onGiveUp(Envelope in, String reason) { return new TestJobHandlers.Ping().onGiveUp(in, reason); }
        };
        QueueWorker jobs = startJobsWorker(failing);
        awaitTrue("consumer subscribes", 15_000, jobs::isConsuming);

        // attempt 1 fails: the message waits in retry tier 1 with attempt 2
        publishPing(ping("a1b2c3d4-0000-4000-8000-000000000001", 1));
        awaitTrue("message in retry tier 1", 5_000, () -> messages("die.test.jobs.retry.1") == 1);
        assertThat(messages("die.test.results")).isZero();

        // the last attempt fails: FAILED result for the API, original message in the DLQ, nothing left behind
        publishPing(ping("a1b2c3d4-0000-4000-8000-000000000002", 3));
        awaitTrue("exhausted message in the DLQ", 10_000, () -> messages("die.test.jobs.dlq") == 1);
        Envelope failedResult = awaitEnvelope("die.test.results");
        assertThat(failedResult.type()).isEqualTo("test.result");
        assertThat(failedResult.payload()).containsEntry("outcome", "FAILED");
        GetResponse dead = test.basicGet("die.test.jobs.dlq", true);
        assertThat(String.valueOf(dead.getProps().getHeaders().get("x-die-failure-reason"))).contains("retries exhausted");
        assertThat(messages("die.test.jobs")).isZero();
    }

    @Test
    void poisonGoesToTheDlqAndTheWorkerKeepsGoing() throws Exception {
        QueueWorker jobs = startJobsWorker(new TestJobHandlers.Ping());
        awaitTrue("consumer subscribes", 15_000, jobs::isConsuming);

        test.basicPublish("die.test", "die.test.jobs", persistent(null), "not json at all".getBytes());
        awaitTrue("poison in the DLQ", 10_000, () -> messages("die.test.jobs.dlq") == 1);

        publishPing(ping("a1b2c3d4-0000-4000-8000-000000000001", 1));
        awaitTrue("a normal job after the poison", 10_000, () -> jobs.handledCount() == 1);
    }

    @Test
    void theWorkerResubscribesAfterTheBrokerRestarts() throws Exception {
        QueueWorker jobs = startJobsWorker(new TestJobHandlers.Ping());
        awaitTrue("consumer subscribes", 15_000, jobs::isConsuming);

        rabbit.getDockerClient().stopContainerCmd(rabbit.getContainerId()).withTimeout(5).exec();
        awaitTrue("worker notices the outage", 30_000, () -> !jobs.isConsuming());
        startBrokerAgain();

        awaitTrue("worker resubscribes on its own", 60_000, jobs::isConsuming);
        testConnection = rawConnection();
        test = testConnection.createChannel();
        publishPing(ping("a1b2c3d4-0000-4000-8000-000000000001", 1));
        awaitTrue("job handled after reconnect", 15_000, () -> jobs.handledCount() == 1);
        assertThat(messages("die.test.analyze")).isEqualTo(1);
    }

    // ---- helpers ----

    private byte[] ping(String id, int attempt) {
        return codec.encode(new Envelope(1, id, "req-20261018-0001", "test.ping", "2026-10-18T09:30:00.123Z", attempt,
            Map.of("testJobId", JOB, "note", "hello", "simulateFailure", false)));
    }

    private void publishPing(byte[] body) throws IOException {
        test.basicPublish("die.test", "die.test.jobs", persistent("test.ping"), body);
    }

    private void publishAnalyzeResult(Envelope analyze, String outcome) throws IOException {
        Envelope result = analyze.derive("analyze-result", "test.analyze.result",
            Map.of("testJobId", JOB, "outcome", outcome, "worker", "document-analysis",
                "result", Map.of("pong", true, "noteLength", 5)));
        test.basicPublish("die.test", "die.test.analyze.results", persistent("test.analyze.result"), codec.encode(result));
    }

    private static AMQP.BasicProperties persistent(String type) {
        AMQP.BasicProperties.Builder b = new AMQP.BasicProperties.Builder().deliveryMode(2).contentType("application/json");
        if (type != null) b.type(type);
        return b.build();
    }

    private Envelope awaitEnvelope(String queue) throws Exception {
        long end = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < end) {
            GetResponse r = test.basicGet(queue, true);
            if (r != null) {
                assertThat(r.getProps().getDeliveryMode()).as("persistent").isEqualTo(2);
                return codec.decode(r.getBody());
            }
            Thread.sleep(100);
        }
        throw new AssertionError("no message arrived on " + queue);
    }

    private int messages(String queue) {
        try {
            return test.queueDeclarePassive(queue).getMessageCount();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @FunctionalInterface
    interface Condition {
        boolean check() throws Exception;
    }

    private static void awaitTrue(String what, long timeoutMs, Condition condition) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        Exception last = null;
        while (System.currentTimeMillis() < end) {
            try {
                if (condition.check()) return;
            } catch (Exception e) {
                last = e;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Timed out waiting for: " + what + (last == null ? "" : " (last error: " + last + ")"));
    }

    private static Connection rawConnection() throws Exception {
        ConnectionFactory f = new ConnectionFactory();
        f.setHost(rabbit.getHost());
        f.setPort(PORT);
        f.setUsername(USER);
        f.setPassword(PASSWORD);
        f.setConnectionTimeout(3_000);
        return f.newConnection("test");
    }

    private static void ensureBrokerIsUp() throws Exception {
        if (!Boolean.TRUE.equals(rabbit.getDockerClient().inspectContainerCmd(rabbit.getContainerId()).exec().getState().getRunning())) {
            startBrokerAgain();
        }
    }

    private static void startBrokerAgain() throws Exception {
        rabbit.getDockerClient().startContainerCmd(rabbit.getContainerId()).exec();
        awaitTopology();
    }

    private static void awaitTopology() throws Exception {
        awaitTrue("broker accepts connections and has the topology", 90_000, () -> {
            try (Connection c = rawConnection(); Channel ch = c.createChannel()) {
                for (String q : QUEUES) ch.queueDeclarePassive(q);
                return true;
            }
        });
    }

    private static List<String> queues() {
        List<String> all = new ArrayList<>();
        for (String q : List.of("die.test.jobs", "die.test.analyze", "die.test.analyze.results", "die.test.results")) {
            all.add(q);
            all.add(q + ".retry.1");
            all.add(q + ".retry.2");
            all.add(q + ".dlq");
        }
        return all;
    }

    private static Path infra(String relative) {
        return Path.of(System.getProperty("die.infra.dir", "../../infra")).resolve(relative).toAbsolutePath().normalize();
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
