package com.blissfuljuan.dieapi.messaging.service;

import com.blissfuljuan.dieapi.messaging.model.OutboundMessage;
import com.blissfuljuan.dieapi.messaging.model.OutboxEvent;
import com.blissfuljuan.dieapi.messaging.model.PublishFailedException;
import com.blissfuljuan.dieapi.messaging.repository.OutboxRepository;
import com.blissfuljuan.dieapi.messaging.service.port.MessagePublisher;
import com.blissfuljuan.dieapi.common.util.ProblemDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Transactional outbox, publish side. Leases due rows (short transaction), publishes each with a publisher
 * confirm (no transaction, no lock), then marks it PUBLISHED. A failure keeps the row PENDING with exponential
 * backoff: an unreachable broker never exhausts anything, a broker that refuses a message does (it is parked as
 * FAILED after {@code max-refusals} refusals). A crash between confirm and mark republishes the row after the
 * lease expires; consumers dedupe on the unchanged messageId.
 */
@Service
@ConditionalOnProperty(name = "die.messaging.enabled", havingValue = "true")
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final MessagePublisher publisher;
    private final int batchSize;
    private final int leaseSeconds;
    private final int maxBackoffSeconds;
    private final int maxRefusals;

    public OutboxRelay(OutboxRepository outbox, MessagePublisher publisher,
                       @Value("${die.messaging.relay.batch-size:50}") int batchSize,
                       @Value("${die.messaging.relay.lease-seconds:30}") int leaseSeconds,
                       @Value("${die.messaging.relay.max-backoff-seconds:30}") int maxBackoffSeconds,
                       @Value("${die.messaging.relay.max-refusals:10}") int maxRefusals) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.batchSize = batchSize;
        this.leaseSeconds = leaseSeconds;
        this.maxBackoffSeconds = maxBackoffSeconds;
        this.maxRefusals = maxRefusals;
    }

    /** One relay pass. @return number of messages confirmed by the broker */
    public int relayOnce() {
        List<OutboxEvent> due = outbox.claimDue(batchSize, leaseSeconds);
        int published = 0;
        for (OutboxEvent event : due) {
            MDC.put(ProblemDetails.REQUEST_ID_KEY, String.valueOf(event.correlationId()));
            MDC.put("messageId", event.id().toString());
            try {
                publisher.publish(new OutboundMessage(event.destination(),
                    event.payloadJson().getBytes(StandardCharsets.UTF_8), event.id(), event.correlationId(),
                    event.messageType(), Map.of()));
                outbox.markPublished(event.id());
                published++;
                log.info("outbox message published to {}/{}", event.exchange(), event.routingKey());
            } catch (PublishFailedException e) {
                boolean giveUp = !e.isBrokerUnavailable() && event.attempts() + 1 >= maxRefusals;
                int backoff = (int) Math.min(maxBackoffSeconds, 1L << Math.min(event.attempts(), 10));
                outbox.markFailed(event.id(), truncate(e.getMessage()), backoff, giveUp);
                log.warn("outbox publish failed (attempt {}, retry in {}s{}): {}", event.attempts() + 1, backoff,
                    giveUp ? ", giving up" : "", e.getMessage());
                if (e.isBrokerUnavailable()) {
                    break;   // the rest of the batch would fail the same way; their leases expire on their own
                }
            } finally {
                MDC.remove(ProblemDetails.REQUEST_ID_KEY);
                MDC.remove("messageId");
            }
        }
        return published;
    }

    private static String truncate(String s) {
        return s == null ? "unknown" : s.length() > 500 ? s.substring(0, 500) : s;
    }
}
