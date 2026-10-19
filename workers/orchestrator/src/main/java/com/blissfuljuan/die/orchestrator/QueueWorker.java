package com.blissfuljuan.die.orchestrator;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Consumes one queue with manual acknowledgements and a prefetch limit, and settles every delivery:
 * <ul>
 *   <li>invalid envelope: dead-letter the raw bytes, ack</li>
 *   <li>duplicate (queue, messageId) already handled: ack, nothing published</li>
 *   <li>handled: publish every output (each confirmed by the broker), remember the id, ack</li>
 *   <li>permanent failure: publish the give-up notices, dead-letter, ack</li>
 *   <li>other failure: republish with attempt + 1 to the next retry tier (backoff by tier TTL), or once the attempts are
 *       used up publish the give-up notices and dead-letter; ack after the republication was confirmed</li>
 *   <li>the outcome itself cannot be published (broker trouble): nack with requeue, the broker redelivers later</li>
 * </ul>
 * A watchdog resubscribes every 2 s when the channel is gone, which is the reconnect handling. A worker that dies
 * mid-job never acked, so the broker redelivers; the derived message ids make the repeated output harmless.
 */
public final class QueueWorker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(QueueWorker.class);

    private final Topology.Queue queue;
    private final Handler handler;
    private final EnvelopeCodec codec;
    private final Publisher publisher;
    private final BrokerConnection connection;
    private final ProcessedMessages processed;
    private final int prefetch;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger handled = new AtomicInteger();
    private ScheduledExecutorService watchdog;
    private volatile Channel channel;
    private volatile String consumerTag;
    private volatile boolean stopping;

    public QueueWorker(Topology.Queue queue, Handler handler, EnvelopeCodec codec, Publisher publisher,
                       BrokerConnection connection, ProcessedMessages processed, int prefetch) {
        this.queue = queue;
        this.handler = handler;
        this.codec = codec;
        this.publisher = publisher;
        this.connection = connection;
        this.processed = processed;
        this.prefetch = prefetch;
    }

    public void start() {
        watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "watchdog-" + queue.name());
            t.setDaemon(true);
            return t;
        });
        watchdog.scheduleWithFixedDelay(this::ensureConsuming, 0, 2, TimeUnit.SECONDS);
    }

    public boolean isConsuming() {
        Channel ch = channel;
        return ch != null && ch.isOpen() && consumerTag != null;
    }

    public int handledCount() {
        return handled.get();
    }

    private void ensureConsuming() {
        if (stopping || isConsuming()) {
            return;
        }
        try {
            Channel ch = connection.get().createChannel();
            ch.basicQos(prefetch);
            consumerTag = null;
            channel = ch;
            consumerTag = ch.basicConsume(queue.name(), false, new DefaultConsumer(ch) {
                @Override
                public void handleDelivery(String tag, com.rabbitmq.client.Envelope env, AMQP.BasicProperties props,
                                           byte[] body) {
                    deliver(ch, env.getDeliveryTag(), body);
                }
            });
            log.info("consuming {} (prefetch {})", queue.name(), prefetch);
        } catch (IOException | TimeoutException | RuntimeException e) {
            consumerTag = null;
            log.warn("cannot consume {} yet ({}); will retry", queue.name(), e.getClass().getSimpleName());
        }
    }

    /** Package-private: tests drive settlement without a broker. */
    void deliver(Channel ch, long deliveryTag, byte[] body) {
        inFlight.incrementAndGet();
        try {
            Envelope envelope;
            try {
                envelope = codec.decode(body);
            } catch (InvalidEnvelopeException e) {
                log.warn("poison message dead-lettered: {}", e.problems());
                deadLetter(body, null, "invalid envelope: " + e.problems());
                ack(ch, deliveryTag);
                return;
            }
            MDC.put("correlationId", envelope.correlationId());
            MDC.put("messageId", envelope.messageId());
            MDC.put("messageType", envelope.type());
            settle(ch, deliveryTag, envelope, body);
        } catch (RuntimeException e) {
            // The outcome could not be routed (broker unavailable): leave the message for redelivery.
            log.error("could not settle message, leaving it for redelivery: {}", e.toString());
            try {
                ch.basicNack(deliveryTag, false, true);
            } catch (IOException | RuntimeException ignored) {
                // the channel is gone; the broker requeues unacknowledged messages itself
            }
        } finally {
            MDC.clear();
            inFlight.decrementAndGet();
        }
    }

    private void settle(Channel ch, long tag, Envelope envelope, byte[] body) {
        if (processed.seen(queue.name(), envelope.messageId())) {
            log.info("duplicate delivery ignored");
            ack(ch, tag);
            return;
        }
        try {
            List<Handler.Outgoing> outputs = handler.handle(envelope);
            for (Handler.Outgoing out : outputs) {
                publish(out);
            }
            processed.remember(queue.name(), envelope.messageId());
            handled.incrementAndGet();
            log.info("handled {} -> {} message(s)", envelope.type(), outputs.size());
        } catch (Handler.PermanentFailure e) {
            log.warn("message dead-lettered: {}", e.getMessage());
            giveUp(envelope, body, e.getMessage());
        } catch (PublishFailedException e) {
            throw e;     // outcome not routable: handled by deliver() as "leave for redelivery"
        } catch (RuntimeException e) {
            log.warn("handling failed on attempt {}: {}", envelope.attempt(), e.toString());
            retryOrGiveUp(envelope, body, e.toString());
        }
        ack(ch, tag);
    }

    private void retryOrGiveUp(Envelope envelope, byte[] body, String reason) {
        Topology.Decision decision = queue.decide(envelope.attempt());
        if (decision.retry()) {
            Envelope next = envelope.nextAttempt();
            publisher.publish(queue.retryDestination(decision.tier()), codec.encode(next), next.messageId(),
                next.correlationId(), next.type(), Map.of("x-die-retry-reason", truncate(reason)));
            log.info("scheduled retry {} in tier {}", next.attempt(), decision.tier());
        } else {
            log.error("retries exhausted after {} attempts; dead-lettered", envelope.attempt());
            giveUp(envelope, body, "retries exhausted: " + reason);
        }
    }

    private void giveUp(Envelope envelope, byte[] body, String reason) {
        for (Handler.Outgoing notice : handler.onGiveUp(envelope, reason)) {
            publish(notice);
        }
        deadLetter(body, envelope, reason);
    }

    private void publish(Handler.Outgoing out) {
        Envelope e = out.envelope();
        publisher.publish(out.destination(), codec.encode(e), e.messageId(), e.correlationId(), e.type(), Map.of());
    }

    private void deadLetter(byte[] body, Envelope envelope, String reason) {
        publisher.publish(queue.deadLetterDestination(), body, envelope == null ? null : envelope.messageId(),
            envelope == null ? null : envelope.correlationId(), envelope == null ? null : envelope.type(),
            Map.of("x-die-failure-reason", truncate(reason), "x-die-source-queue", queue.name()));
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

    /** Stops taking new deliveries, then waits (up to {@code timeoutMillis}) for the ones in flight. */
    public void stop(long timeoutMillis) {
        stopping = true;
        if (watchdog != null) {
            watchdog.shutdownNow();
        }
        Channel ch = channel;
        String tag = consumerTag;
        if (ch != null && tag != null && ch.isOpen()) {
            try {
                ch.basicCancel(tag);
            } catch (IOException | RuntimeException e) {
                log.debug("cancel failed", e);
            }
        }
        long end = System.currentTimeMillis() + timeoutMillis;
        while (inFlight.get() > 0 && System.currentTimeMillis() < end) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (ch != null && ch.isOpen()) {
            try {
                ch.close();
            } catch (IOException | TimeoutException | RuntimeException ignored) {
                // shutting down
            }
        }
    }

    @Override
    public void close() {
        stop(5_000);
    }
}
