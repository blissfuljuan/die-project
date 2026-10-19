package com.blissfuljuan.dieapi.messaging.service.adapter;

import com.blissfuljuan.dieapi.messaging.model.Envelope;
import com.blissfuljuan.dieapi.messaging.model.InvalidEnvelopeException;
import com.blissfuljuan.dieapi.messaging.model.OutboundMessage;
import com.blissfuljuan.dieapi.messaging.model.PermanentMessageException;
import com.blissfuljuan.dieapi.messaging.model.QueueTopology;
import com.blissfuljuan.dieapi.messaging.model.RetryPolicy;
import com.blissfuljuan.dieapi.messaging.service.EnvelopeCodec;
import com.blissfuljuan.dieapi.messaging.service.InboundProcessor;
import com.blissfuljuan.dieapi.messaging.service.port.MessagePublisher;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;
import com.blissfuljuan.dieapi.common.util.ProblemDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Consumes the configured queues with manual acknowledgements and a prefetch limit. A watchdog re-subscribes every
 * few seconds, which is the reconnect handling: it works whether the broker is down at startup, restarts, or drops the
 * connection. Per message: invalid envelope -> DLQ; handled (or duplicate) -> ack; permanent failure -> DLQ; other
 * failure -> republish with attempt + 1 to the next retry tier, or to the DLQ once the attempts are used up. The
 * republication is confirmed before the original is acknowledged, so a message is never lost (it may be duplicated;
 * the inbox makes that harmless).
 */
@Component
@ConditionalOnProperty(name = "die.messaging.enabled", havingValue = "true")
public class RabbitInboundConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RabbitInboundConsumer.class);

    private final RabbitConnection connections;
    private final MessagePublisher publisher;
    private final InboundProcessor processor;
    private final EnvelopeCodec codec;
    private final List<QueueTopology> queues = new ArrayList<>();
    private final Map<String, Channel> channels = new java.util.concurrent.ConcurrentHashMap<>();
    private final int prefetch;
    private ScheduledExecutorService watchdog;
    private volatile boolean running;

    public RabbitInboundConsumer(RabbitConnection connections, MessagePublisher publisher, InboundProcessor processor,
                                 EnvelopeCodec codec,
                                 @Value("${die.messaging.consume-queues:die.test.results}") List<String> queueNames,
                                 @Value("${die.messaging.prefetch:10}") int prefetch) {
        this.connections = connections;
        this.publisher = publisher;
        this.processor = processor;
        this.codec = codec;
        this.prefetch = prefetch;
        for (String q : queueNames) {
            if (!q.isBlank()) {
                String name = q.trim();
                queues.add(QueueTopology.of(name.substring(0, name.indexOf('.', name.indexOf('.') + 1)), name));
            }
        }
    }

    @Override
    public void start() {
        running = true;
        watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rabbit-consumer-watchdog");
            t.setDaemon(true);
            return t;
        });
        watchdog.scheduleWithFixedDelay(this::ensureConsuming, 0, 2, TimeUnit.SECONDS);
    }

    private void ensureConsuming() {
        for (QueueTopology topology : queues) {
            Channel ch = channels.get(topology.queue());
            if (running && (ch == null || !ch.isOpen())) {
                try {
                    subscribe(topology);
                } catch (IOException | TimeoutException | RuntimeException e) {
                    log.warn("cannot consume {} yet ({}); will retry", topology.queue(), e.getClass().getSimpleName());
                }
            }
        }
    }

    private void subscribe(QueueTopology topology) throws IOException, TimeoutException {
        Channel ch = connections.get().createChannel();
        ch.basicQos(prefetch);
        ch.basicConsume(topology.queue(), false, new DefaultConsumer(ch) {
            @Override
            public void handleDelivery(String tag, com.rabbitmq.client.Envelope envelope,
                                       com.rabbitmq.client.AMQP.BasicProperties props, byte[] body) {
                deliver(ch, envelope.getDeliveryTag(), topology, body);
            }
        });
        channels.put(topology.queue(), ch);
        log.info("consuming {} (prefetch {})", topology.queue(), prefetch);
    }

    /** Package-private so tests can drive the decision logic without a broker. */
    void deliver(Channel ch, long deliveryTag, QueueTopology topology, byte[] body) {
        Envelope envelope = null;
        try {
            try {
                envelope = codec.decode(body);
            } catch (InvalidEnvelopeException e) {
                log.warn("poison message dead-lettered: {}", e.problems());
                deadLetter(topology, body, null, "invalid envelope: " + e.problems());
                ack(ch, deliveryTag);
                return;
            }
            MDC.put(ProblemDetails.REQUEST_ID_KEY, envelope.correlationId());
            MDC.put("messageId", envelope.messageId().toString());
            try {
                processor.process(topology.queue(), envelope);
            } catch (PermanentMessageException e) {
                log.warn("message dead-lettered: {}", e.getMessage());
                deadLetter(topology, body, envelope, e.getMessage());
            } catch (RuntimeException e) {
                log.warn("handling failed on attempt {}: {}", envelope.attempt(), e.toString());
                retryOrDeadLetter(topology, body, envelope, e.toString());
            }
            ack(ch, deliveryTag);
        } catch (RuntimeException e) {
            // Could not even route the failure (broker unavailable): leave the message unacknowledged so the broker
            // redelivers it once we are connected again.
            log.error("could not settle message, leaving it for redelivery: {}", e.toString());
            try {
                ch.basicNack(deliveryTag, false, true);
            } catch (IOException | RuntimeException ignored) {
                // channel is gone; the broker requeues unacknowledged messages itself
            }
        } finally {
            MDC.remove(ProblemDetails.REQUEST_ID_KEY);
            MDC.remove("messageId");
        }
    }

    private void retryOrDeadLetter(QueueTopology topology, byte[] originalBody, Envelope envelope, String reason) {
        RetryPolicy.Decision decision = topology.retry().decide(envelope.attempt());
        if (decision.action() == RetryPolicy.Action.DEAD_LETTER) {
            log.error("retries exhausted after {} attempts; dead-lettered", envelope.attempt());
            deadLetter(topology, originalBody, envelope, "retries exhausted: " + reason);
            return;
        }
        Envelope next = envelope.nextAttempt();
        publisher.publish(new OutboundMessage(topology.retryDestination(decision.tier()),
            codec.encode(next).getBytes(java.nio.charset.StandardCharsets.UTF_8), next.messageId(),
            next.correlationId(), next.type(), Map.of("x-die-retry-reason", truncate(reason))));
        log.info("scheduled retry {} in tier {}", next.attempt(), decision.tier());
    }

    private void deadLetter(QueueTopology topology, byte[] body, Envelope envelope, String reason) {
        publisher.publish(new OutboundMessage(topology.deadLetterDestination(), body,
            envelope == null ? null : envelope.messageId(), envelope == null ? null : envelope.correlationId(),
            envelope == null ? null : envelope.type(),
            Map.of("x-die-failure-reason", truncate(reason), "x-die-source-queue", topology.queue())));
    }

    private static void ack(Channel ch, long tag) {
        try {
            ch.basicAck(tag, false);
        } catch (IOException e) {
            throw new IllegalStateException("ack failed", e);
        }
    }

    private static String truncate(String s) {
        return s == null ? "" : s.length() > 400 ? s.substring(0, 400) : s;
    }

    @Override
    public void stop() {
        running = false;
        if (watchdog != null) {
            watchdog.shutdownNow();
        }
        channels.values().forEach(ch -> {
            try {
                if (ch.isOpen()) ch.close();
            } catch (IOException | TimeoutException | RuntimeException ignored) {
                // shutting down
            }
        });
        channels.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
