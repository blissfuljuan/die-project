package com.blissfuljuan.die.orchestrator;

import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Settlement decisions of the orchestrator's consumer without a broker. */
class QueueWorkerTest {

    private static final String JOB = "3f2b8c1e-5d4a-4e7b-9a60-1c2d3e4f5a6b";

    record Sent(Topology.Destination destination, byte[] body, String messageId, Map<String, Object> headers) {}

    private final EnvelopeCodec codec = new EnvelopeCodec();
    private final List<Sent> sent = new ArrayList<>();
    private final Channel channel = mock(Channel.class);
    private boolean brokerDown;
    private RuntimeException handlerFailure;
    private QueueWorker worker;

    @BeforeEach
    void setUp() {
        Publisher publisher = (destination, body, messageId, correlationId, type, headers) -> {
            if (brokerDown) throw new PublishFailedException("down", null);
            sent.add(new Sent(destination, body, messageId, headers));
        };
        Handler handler = new Handler() {
            private final Handler real = new TestJobHandlers.Ping();
            public List<Outgoing> handle(Envelope in) {
                if (handlerFailure != null) throw handlerFailure;
                return real.handle(in);
            }
            public List<Outgoing> onGiveUp(Envelope in, String reason) { return real.onGiveUp(in, reason); }
        };
        worker = new QueueWorker(Topology.JOBS, handler, codec, publisher, new BrokerConnection(new Settings("h", 5672, "u", "p", "/", 0, 5)),
            new ProcessedMessages(100), 5);
    }

    private byte[] ping(String id, int attempt) {
        return codec.encode(new Envelope(1, id, "req-20261018-0001", "test.ping", "2026-10-18T09:30:00.123Z", attempt,
            Map.of("testJobId", JOB, "note", "hello", "simulateFailure", false)));
    }

    private static final String ID = "a1b2c3d4-0000-4000-8000-000000000001";

    @Test
    void aHandledPingPublishesProgressAndForwardThenAcknowledges() throws IOException {
        worker.deliver(channel, 1, ping(ID, 1));

        assertThat(sent).extracting(s -> s.destination().routingKey()).containsExactly("die.test.results", "die.test.analyze");
        verify(channel).basicAck(1, false);
        assertThat(worker.handledCount()).isEqualTo(1);
    }

    @Test
    void aDuplicateDeliveryIsAcknowledgedWithoutPublishingAgain() throws IOException {
        worker.deliver(channel, 1, ping(ID, 1));
        sent.clear();
        worker.deliver(channel, 2, ping(ID, 1));

        assertThat(sent).isEmpty();
        verify(channel).basicAck(2, false);
        assertThat(worker.handledCount()).isEqualTo(1);
    }

    @Test
    void firstFailureGoesToRetryTierOneWithTheNextAttempt() throws IOException {
        handlerFailure = new IllegalStateException("boom");
        worker.deliver(channel, 3, ping(ID, 1));

        assertThat(sent).singleElement().satisfies(s -> {
            assertThat(s.destination()).isEqualTo(new Topology.Destination("die.test.retry", "die.test.jobs.retry.1"));
            assertThat(codec.decode(s.body()).attempt()).isEqualTo(2);
            assertThat(s.messageId()).isEqualTo(ID);
        });
        verify(channel).basicAck(3, false);
    }

    @Test
    void secondFailureGoesToTierTwo() {
        handlerFailure = new IllegalStateException("boom");
        worker.deliver(channel, 4, ping(ID, 2));
        assertThat(sent).singleElement().satisfies(s -> assertThat(s.destination().routingKey()).isEqualTo("die.test.jobs.retry.2"));
    }

    @Test
    void exhaustedRetriesReportFailureToTheApiThenDeadLetter() throws IOException {
        handlerFailure = new IllegalStateException("boom");
        byte[] original = ping(ID, 3);
        worker.deliver(channel, 5, original);

        assertThat(sent).hasSize(2);
        assertThat(sent.get(0).destination()).isEqualTo(Topology.RESULTS);
        assertThat(codec.decode(sent.get(0).body()).payload()).containsEntry("outcome", "FAILED").containsEntry("testJobId", JOB);
        assertThat(sent.get(1).destination()).isEqualTo(new Topology.Destination("die.test.dlx", "die.test.jobs"));
        assertThat(sent.get(1).body()).isEqualTo(original);
        assertThat(sent.get(1).headers()).containsKey("x-die-failure-reason");
        verify(channel).basicAck(5, false);
    }

    @Test
    void permanentFailureIsDeadLetteredWithoutRetries() {
        handlerFailure = new Handler.PermanentFailure("cannot ever work");
        worker.deliver(channel, 6, ping(ID, 1));
        assertThat(sent).extracting(s -> s.destination().exchange()).containsExactly("die.test", "die.test.dlx");
    }

    @Test
    void poisonBytesAreDeadLetteredAsReceived() throws IOException {
        byte[] garbage = "}{ nope".getBytes();
        worker.deliver(channel, 7, garbage);

        assertThat(sent).singleElement().satisfies(s -> {
            assertThat(s.destination().exchange()).isEqualTo("die.test.dlx");
            assertThat(s.body()).isEqualTo(garbage);
            assertThat(s.messageId()).isNull();
        });
        verify(channel).basicAck(7, false);
    }

    @Test
    void whenTheOutcomeCannotBePublishedTheMessageIsLeftForRedelivery() throws IOException {
        brokerDown = true;
        worker.deliver(channel, 8, ping(ID, 1));

        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(channel).basicNack(8, false, true);
        assertThat(worker.handledCount()).isZero();
    }

    @Test
    void aMessageThatFailedToPublishIsNotRememberedSoItIsProcessedWhenRedelivered() throws IOException {
        brokerDown = true;
        worker.deliver(channel, 9, ping(ID, 1));
        brokerDown = false;
        worker.deliver(channel, 10, ping(ID, 1));

        assertThat(sent).hasSize(2);
        verify(channel).basicAck(10, false);
    }
}
