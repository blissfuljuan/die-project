package com.blissfuljuan.dieapi.messaging.service.adapter;

import com.blissfuljuan.dieapi.messaging.model.Envelope;
import com.blissfuljuan.dieapi.messaging.model.OutboundMessage;
import com.blissfuljuan.dieapi.messaging.model.PermanentMessageException;
import com.blissfuljuan.dieapi.messaging.model.PublishFailedException;
import com.blissfuljuan.dieapi.messaging.model.QueueTopology;
import com.blissfuljuan.dieapi.messaging.repository.InboxRepository;
import com.blissfuljuan.dieapi.messaging.service.EnvelopeCodec;
import com.blissfuljuan.dieapi.messaging.service.EnvelopeSchemaValidator;
import com.blissfuljuan.dieapi.messaging.service.InboundProcessor;
import com.blissfuljuan.dieapi.messaging.service.port.InboundMessageHandler;
import com.blissfuljuan.dieapi.messaging.service.port.MessagePublisher;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Settlement decisions of the API consumer, without a broker: ack / retry tier / DLQ / leave for redelivery. */
class RabbitInboundConsumerTest {

    private static final QueueTopology TOPOLOGY = QueueTopology.of("die.test", "die.test.results");

    private final EnvelopeCodec codec = new EnvelopeCodec(JsonMapper.builder().build(), new EnvelopeSchemaValidator());
    private final List<OutboundMessage> published = new ArrayList<>();
    private final Channel channel = mock(Channel.class);
    private final InboxRepository inbox = mock(InboxRepository.class);
    private RuntimeException handlerFailure;
    private boolean publisherDown;
    private RabbitInboundConsumer consumer;

    @BeforeEach
    void setUp() {
        MessagePublisher publisher = m -> {
            if (publisherDown) throw new PublishFailedException("down", true, null);
            published.add(m);
        };
        InboundMessageHandler handler = new InboundMessageHandler() {
            public String messageType() { return "test.result"; }
            public void handle(Envelope e) { if (handlerFailure != null) throw handlerFailure; }
        };
        InboundProcessor processor = new InboundProcessor(inbox, List.of(handler));
        when(inbox.recordIfNew(anyString(), any())).thenReturn(true);
        consumer = new RabbitInboundConsumer(mock(RabbitConnection.class), publisher, processor, codec,
            List.of("die.test.results"), 10);
    }

    private byte[] message(int attempt) {
        return codec.encode(new Envelope(1, UUID.randomUUID(), "req-12345678", "test.result", Instant.now(), attempt,
            Map.of("testJobId", UUID.randomUUID().toString(), "outcome", "SUCCEEDED", "worker", "w"))).getBytes();
    }

    @Test
    void successIsAcknowledgedAndNothingIsPublished() throws IOException {
        consumer.deliver(channel, 1, TOPOLOGY, message(1));
        verify(channel).basicAck(1, false);
        assertThat(published).isEmpty();
    }

    @Test
    void duplicateDeliveryIsAcknowledgedWithoutRunningTheHandler() throws IOException {
        when(inbox.recordIfNew(anyString(), any())).thenReturn(false);
        handlerFailure = new IllegalStateException("must not run");
        consumer.deliver(channel, 2, TOPOLOGY, message(1));
        verify(channel).basicAck(2, false);
        assertThat(published).isEmpty();
    }

    @Test
    void firstFailureGoesToRetryTierOneWithTheNextAttempt() throws IOException {
        handlerFailure = new IllegalStateException("db down");
        consumer.deliver(channel, 3, TOPOLOGY, message(1));
        assertThat(published).singleElement().satisfies(m -> {
            assertThat(m.destination().exchange()).isEqualTo("die.test.retry");
            assertThat(m.destination().routingKey()).isEqualTo("die.test.results.retry.1");
            assertThat(codec.decode(m.body()).attempt()).isEqualTo(2);
        });
        verify(channel).basicAck(3, false);
    }

    @Test
    void secondFailureGoesToTierTwo() {
        handlerFailure = new IllegalStateException("db down");
        consumer.deliver(channel, 4, TOPOLOGY, message(2));
        assertThat(published).singleElement()
            .satisfies(m -> assertThat(m.destination().routingKey()).isEqualTo("die.test.results.retry.2"));
    }

    @Test
    void exhaustedRetriesGoToTheDeadLetterQueue() throws IOException {
        handlerFailure = new IllegalStateException("db down");
        consumer.deliver(channel, 5, TOPOLOGY, message(3));
        assertThat(published).singleElement().satisfies(m -> {
            assertThat(m.destination().exchange()).isEqualTo("die.test.dlx");
            assertThat(m.destination().routingKey()).isEqualTo("die.test.results");
            assertThat(m.headers()).containsKey("x-die-failure-reason");
        });
        verify(channel).basicAck(5, false);
    }

    @Test
    void permanentFailureSkipsTheRetries() {
        handlerFailure = new PermanentMessageException("unknown job");
        consumer.deliver(channel, 6, TOPOLOGY, message(1));
        assertThat(published).singleElement()
            .satisfies(m -> assertThat(m.destination().exchange()).isEqualTo("die.test.dlx"));
    }

    @Test
    void poisonBytesAreDeadLetteredAsReceived() throws IOException {
        byte[] garbage = "}{ not an envelope".getBytes();
        consumer.deliver(channel, 7, TOPOLOGY, garbage);
        assertThat(published).singleElement().satisfies(m -> {
            assertThat(m.destination().exchange()).isEqualTo("die.test.dlx");
            assertThat(m.body()).isEqualTo(garbage);
            assertThat(m.messageId()).isNull();
        });
        verify(channel).basicAck(7, false);
    }

    @Test
    void ifTheFailureCannotBeRoutedTheMessageIsNotAcknowledged() throws IOException {
        handlerFailure = new IllegalStateException("db down");
        publisherDown = true;
        consumer.deliver(channel, 8, TOPOLOGY, message(1));
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(channel).basicNack(8, false, true);
    }
}
