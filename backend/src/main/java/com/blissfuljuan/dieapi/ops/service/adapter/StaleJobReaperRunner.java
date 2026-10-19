package com.blissfuljuan.dieapi.ops.service.adapter;

import com.blissfuljuan.dieapi.ops.service.StaleJobReaper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Runs {@link StaleJobReaper#reapOnce()} on a fixed delay. Disable with die.ops.reaper.enabled=false (tests call it by hand). */
@Component
@ConditionalOnProperty(name = "die.ops.reaper.enabled", havingValue = "true", matchIfMissing = true)
public class StaleJobReaperRunner implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(StaleJobReaperRunner.class);

    private final StaleJobReaper reaper;
    private final long intervalSeconds;
    private ScheduledExecutorService executor;
    private volatile boolean running;

    public StaleJobReaperRunner(StaleJobReaper reaper, @Value("${die.ops.reaper.interval-seconds:60}") long intervalSeconds) {
        this.reaper = reaper;
        this.intervalSeconds = intervalSeconds;
    }

    @Override
    public void start() {
        running = true;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "stale-job-reaper");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(() -> {
            try {
                reaper.reapOnce();
            } catch (RuntimeException e) {
                log.error("stale job reaper pass failed: {}", e.toString());
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
