package com.blissfuljuan.dieapi.common.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs one job on a fixed delay on a daemon thread for the life of the application context. A failing pass is logged and the
 * next one still runs. Modules wrap it in a small component so they can switch it off by property.
 */
public class PeriodicTask implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PeriodicTask.class);

    private final String name;
    private final long initialDelaySeconds;
    private final long intervalSeconds;
    private final Runnable job;
    private ScheduledExecutorService executor;
    private volatile boolean running;

    public PeriodicTask(String name, long initialDelaySeconds, long intervalSeconds, Runnable job) {
        this.name = name;
        this.initialDelaySeconds = initialDelaySeconds;
        this.intervalSeconds = intervalSeconds;
        this.job = job;
    }

    @Override
    public void start() {
        running = true;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(() -> {
            try {
                job.run();
            } catch (RuntimeException e) {
                log.error("{} pass failed: {}", name, e.toString());
            }
        }, initialDelaySeconds, intervalSeconds, TimeUnit.SECONDS);
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
