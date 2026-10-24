package com.blissfuljuan.die.orchestrator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Orchestration worker (P0-VS04 scope: connectivity). Consumes die.test.jobs and die.test.analyze.results, forwards
 * work to the document worker and results to the API. Stops cleanly on SIGTERM: no new deliveries, in-flight ones finish.
 */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        Settings settings = Settings.fromEnv(System.getenv());
        EnvelopeCodec codec = new EnvelopeCodec();
        ProcessedMessages processed = new ProcessedMessages(10_000);

        BrokerConnection connection = new BrokerConnection(settings);
        ConfirmingPublisher publisher = new ConfirmingPublisher(connection, 5_000);
        QueueWorker jobs = new QueueWorker(Topology.JOBS, new TestJobHandlers.Ping(), codec, publisher, connection,
            processed, settings.prefetch());
        QueueWorker results = new QueueWorker(Topology.ANALYZE_RESULTS, new TestJobHandlers.AnalyzeResult(), codec,
            publisher, connection, processed, settings.prefetch());

        HealthState health = new HealthState(List.<BooleanSupplier>of(jobs::isConsuming, results::isConsuming));
        HealthServer healthServer = new HealthServer(health, settings.healthPort());
        log.info("orchestrator starting (broker {}:{}, health :{})", settings.host(), settings.port(), healthServer.port());

        jobs.start();
        results.start();

        // Heartbeat: proves the process is alive and records throughput. The HTTP endpoint turns stale if this stops.
        ScheduledExecutorService beat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat");
            t.setDaemon(true);
            return t;
        });
        beat.scheduleWithFixedDelay(() -> {
            health.heartbeat();
            log.info("heartbeat consuming={} handled={}", jobs.isConsuming() && results.isConsuming(),
                jobs.handledCount() + results.handledCount());
        }, 0, 30, TimeUnit.SECONDS);

        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("shutdown requested");
            stop.countDown();
        }, "shutdown"));
        stop.await();

        beat.shutdownNow();
        jobs.stop(10_000);
        results.stop(10_000);
        publisher.close();
        connection.close();
        healthServer.close();
        log.info("orchestrator stopped");
    }

    private Main() {}
}
