package com.blissfuljuan.dieapi.messaging.service.adapter;

import com.blissfuljuan.dieapi.messaging.service.OutboxRelay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Runs {@link OutboxRelay#relayOnce()} on a fixed delay. Disable with die.messaging.relay.enabled=false (tests drive the relay by hand). */
@Component
@ConditionalOnProperty(name = {"die.messaging.enabled", "die.messaging.relay.enabled"}, havingValue = "true", matchIfMissing = false)
public class OutboxRelayRunner implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayRunner.class);

    private final OutboxRelay relay;
    private final long pollMillis;
    private ScheduledExecutorService executor;
    private volatile boolean running;

    public OutboxRelayRunner(OutboxRelay relay, @Value("${die.messaging.relay.poll-ms:500}") long pollMillis) {
        this.relay = relay;
        this.pollMillis = pollMillis;
    }

    @Override
    public void start() {
        running = true;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "outbox-relay");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(() -> {
            try {
                relay.relayOnce();
            } catch (RuntimeException e) {
                log.error("outbox relay pass failed: {}", e.toString());
            }
        }, 0, pollMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        running = false;
        if (executor != null) {
            executor.shutdown();
            try {
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
