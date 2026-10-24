package com.blissfuljuan.dieapi.ops.service;

import com.blissfuljuan.dieapi.messaging.model.BrokerStatus;
import com.blissfuljuan.dieapi.messaging.service.port.BrokerInspector;
import com.blissfuljuan.dieapi.ops.model.JobStats;
import com.blissfuljuan.dieapi.ops.model.OutboxStats;
import com.blissfuljuan.dieapi.ops.model.PlatformHealthLimits;
import com.blissfuljuan.dieapi.ops.model.WorkerHealth;
import com.blissfuljuan.dieapi.ops.repository.PlatformStatsRepository;
import com.blissfuljuan.dieapi.ops.service.port.WorkerHealthProbe;
import com.blissfuljuan.dieapi.ops.view.PlatformHealthView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Builds the administrator health report (database, broker, worker heartbeats, outbox backlog, test-job counts and queue
 * depths). Read-only; every probe is individually fault tolerant so one dead dependency still yields a report.
 */
@Service
public class PlatformHealthService {

    private static final Logger log = LoggerFactory.getLogger(PlatformHealthService.class);

    private final PlatformStatsRepository stats;
    private final ObjectProvider<BrokerInspector> broker;
    private final WorkerHealthProbe workers;
    private final PlatformHealthLimits limits;
    private final List<String> queues;
    private final Clock clock = Clock.systemUTC();

    public PlatformHealthService(PlatformStatsRepository stats, ObjectProvider<BrokerInspector> broker,
                                 WorkerHealthProbe workers,
                                 @Value("${die.ops.max-heartbeat-age-seconds:90}") double maxHeartbeatAgeSeconds,
                                 @Value("${die.ops.reaper.stale-after-seconds:900}") long staleJobSeconds,
                                 @Value("${die.ops.queues:die.test.jobs,die.test.results,die.test.analyze,die.test.analyze.results,"
                                     + "die.test.jobs.dlq,die.test.results.dlq,die.test.analyze.dlq,die.test.analyze.results.dlq}")
                                 String queues) {
        this.stats = stats;
        this.broker = broker;
        this.workers = workers;
        this.limits = new PlatformHealthLimits(maxHeartbeatAgeSeconds, staleJobSeconds);
        this.queues = Arrays.stream(queues.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    public PlatformHealthView report() {
        PlatformHealthView.Dependency database = database();
        BrokerInspector inspector = broker.getIfAvailable();
        PlatformHealthView.Dependency brokerDependency = brokerDependency(inspector);
        Map<String, Long> depths = inspector != null && "UP".equals(brokerDependency.status())
            ? inspector.queueDepths(queues) : Map.of();
        List<PlatformHealthView.Worker> workerViews = workers.probeAll().stream().map(this::worker).toList();

        PlatformHealthView.Outbox outbox = null;
        PlatformHealthView.Jobs jobs = null;
        if ("UP".equals(database.status())) {
            OutboxStats o = stats.outbox();
            outbox = new PlatformHealthView.Outbox(o.pending(), o.failed(), o.oldestPendingSeconds());
            JobStats j = stats.testJobs(limits.staleJobSeconds());
            jobs = new PlatformHealthView.Jobs(j.queued(), j.running(), j.succeeded(), j.failed(), j.overdue());
        }
        boolean allUp = "UP".equals(database.status())
            && ("UP".equals(brokerDependency.status()) || "DISABLED".equals(brokerDependency.status()))
            && workerViews.stream().allMatch(w -> ("UP".equals(w.status()) && w.heartbeatFresh())
                || "NOT_CONFIGURED".equals(w.status()));
        return new PlatformHealthView(allUp ? "UP" : "DEGRADED", clock.instant(), database, brokerDependency, workerViews,
            outbox, jobs, depths);
    }

    private PlatformHealthView.Dependency database() {
        long start = System.nanoTime();
        try {
            stats.ping();
            return new PlatformHealthView.Dependency("UP", (System.nanoTime() - start) / 1_000_000, null);
        } catch (RuntimeException e) {
            log.warn("database health check failed: {}", e.toString());
            return new PlatformHealthView.Dependency("DOWN", null, "database unreachable");
        }
    }

    private PlatformHealthView.Dependency brokerDependency(BrokerInspector inspector) {
        if (inspector == null) {
            return new PlatformHealthView.Dependency("DISABLED", null, "messaging is disabled in this deployment");
        }
        BrokerStatus status = inspector.check();
        return new PlatformHealthView.Dependency(status.up() ? "UP" : "DOWN", status.checkMillis(), status.reason());
    }

    private PlatformHealthView.Worker worker(WorkerHealth health) {
        Double age = health.heartbeatAgeSeconds();
        boolean fresh = age != null && age <= limits.maxHeartbeatAgeSeconds();
        return new PlatformHealthView.Worker(health.name(), health.state().name(), age, fresh);
    }
}
