package com.blissfuljuan.dieapi.ops.view;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Administrator report of the platform's dependencies. Contains no hosts, credentials or versions: only states,
 * counts, ages and queue depths. {@code status} is UP when every dependency and configured worker is UP and DEGRADED
 * when something is DOWN (the API itself still answers).
 */
public record PlatformHealthView(String status, Instant checkedAt, Dependency database, Dependency broker,
                                 List<Worker> workers, Outbox outbox, Jobs testJobs, Map<String, Long> queueDepths) {

    public record Dependency(String status, Long responseMillis, String reason) {}

    public record Worker(String name, String status, Double heartbeatAgeSeconds, boolean heartbeatFresh) {}

    public record Outbox(long pending, long failed, Long oldestPendingSeconds) {}

    public record Jobs(long queued, long running, long succeeded, long failed, long overdue) {}
}
