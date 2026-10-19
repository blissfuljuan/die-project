package com.blissfuljuan.die.orchestrator;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestJobHandlersTest {

    private static final String JOB = "3f2b8c1e-5d4a-4e7b-9a60-1c2d3e4f5a6b";
    private final EnvelopeCodec codec = new EnvelopeCodec();

    private static Envelope ping(String id) {
        return new Envelope(1, id, "req-20261018-0001", "test.ping", "2026-10-18T09:30:00.123Z", 1,
            Map.of("testJobId", JOB, "note", "hello", "simulateFailure", false));
    }

    @Test
    void pingProducesProgressForTheApiAndAnalyzeForTheDocumentWorker() {
        List<Handler.Outgoing> out = new TestJobHandlers.Ping().handle(ping("a1b2c3d4-0000-4000-8000-000000000001"));

        assertThat(out).hasSize(2);
        assertThat(out.get(0).destination()).isEqualTo(Topology.RESULTS);
        assertThat(out.get(0).envelope().type()).isEqualTo("test.progress");
        assertThat(out.get(0).envelope().payload()).containsEntry("testJobId", JOB).containsEntry("stage", "orchestrator-started");
        assertThat(out.get(1).destination()).isEqualTo(Topology.ANALYZE);
        assertThat(out.get(1).envelope().type()).isEqualTo("test.analyze");
        assertThat(out.get(1).envelope().payload()).containsEntry("note", "hello").containsEntry("simulateFailure", false);
        out.forEach(o -> {
            assertThat(o.envelope().correlationId()).isEqualTo("req-20261018-0001");
            assertThat(codec.validate(new String(codec.encode(o.envelope())))).isEmpty();
        });
    }

    @Test
    void derivedIdsAreDeterministicSoRedeliveryProducesRecognisableDuplicates() {
        Envelope in = ping("a1b2c3d4-0000-4000-8000-000000000001");
        List<Handler.Outgoing> first = new TestJobHandlers.Ping().handle(in);
        List<Handler.Outgoing> again = new TestJobHandlers.Ping().handle(in);
        assertThat(again.get(0).envelope().messageId()).isEqualTo(first.get(0).envelope().messageId());
        assertThat(again.get(1).envelope().messageId()).isEqualTo(first.get(1).envelope().messageId());
        assertThat(first.get(0).envelope().messageId()).isNotEqualTo(first.get(1).envelope().messageId());
        assertThat(new TestJobHandlers.Ping().handle(ping("a1b2c3d4-0000-4000-8000-000000000002")).get(0).envelope().messageId())
            .isNotEqualTo(first.get(0).envelope().messageId());
    }

    @Test
    void aWrongMessageTypeIsPermanent() {
        Envelope wrong = new Envelope(1, "a1b2c3d4-0000-4000-8000-000000000001", "req-20261018-0001", "test.result",
            "2026-10-18T09:30:00.123Z", 1, Map.of("testJobId", JOB));
        assertThatThrownBy(() -> new TestJobHandlers.Ping().handle(wrong)).isInstanceOf(Handler.PermanentFailure.class);
    }

    @Test
    void theAnalyzeResultIsMappedToATestResultWithTheWorkerChain() {
        Envelope in = new Envelope(1, "a1b2c3d4-0000-4000-8000-0000000000aa", "req-20261018-0001", "test.analyze.result",
            "2026-10-18T09:30:00.123Z", 1,
            Map.of("testJobId", JOB, "outcome", "SUCCEEDED", "worker", "document-analysis",
                "result", Map.of("pong", true, "noteLength", 5)));
        List<Handler.Outgoing> out = new TestJobHandlers.AnalyzeResult().handle(in);

        assertThat(out).hasSize(1);
        Envelope result = out.get(0).envelope();
        assertThat(out.get(0).destination()).isEqualTo(Topology.RESULTS);
        assertThat(result.type()).isEqualTo("test.result");
        assertThat(result.payload()).containsEntry("outcome", "SUCCEEDED")
            .containsEntry("workers", List.of("orchestrator", "document-analysis"));
        assertThat(codec.validate(new String(codec.encode(result)))).isEmpty();
    }

    @Test
    void aFailedAnalysisIsPassedOnWithItsReason() {
        Envelope in = new Envelope(1, "a1b2c3d4-0000-4000-8000-0000000000aa", "req-20261018-0001", "test.analyze.result",
            "2026-10-18T09:30:00.123Z", 1,
            Map.of("testJobId", JOB, "outcome", "FAILED", "worker", "document-analysis", "reason", "retries exhausted"));
        Envelope result = new TestJobHandlers.AnalyzeResult().handle(in).get(0).envelope();
        assertThat(result.payload()).containsEntry("outcome", "FAILED").containsEntry("reason", "retries exhausted");
    }

    @Test
    void givingUpOnAPingReportsTheJobAsFailed() {
        List<Handler.Outgoing> notice = new TestJobHandlers.Ping().onGiveUp(ping("a1b2c3d4-0000-4000-8000-000000000001"), "retries exhausted: boom");
        assertThat(notice).singleElement().satisfies(o -> {
            assertThat(o.destination()).isEqualTo(Topology.RESULTS);
            assertThat(o.envelope().payload()).containsEntry("testJobId", JOB).containsEntry("outcome", "FAILED");
            assertThat(String.valueOf(o.envelope().payload().get("reason"))).contains("boom");
            assertThat(codec.validate(new String(codec.encode(o.envelope())))).isEmpty();
        });
    }
}
