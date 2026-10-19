package com.blissfuljuan.die.orchestrator;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Heartbeat and readiness of the worker, read by the HTTP health endpoint. */
public final class HealthState {

    static final long LIVENESS_GRACE_MILLIS = 90_000;

    private final AtomicLong lastHeartbeat = new AtomicLong(System.nanoTime());
    private final List<BooleanSupplier> readiness;
    private volatile long graceMillis = LIVENESS_GRACE_MILLIS;

    public HealthState(List<BooleanSupplier> readiness) {
        this.readiness = readiness;
    }

    public void heartbeat() {
        lastHeartbeat.set(System.nanoTime());
    }

    void setGraceMillis(long millis) {
        this.graceMillis = millis;
    }

    public long millisSinceHeartbeat() {
        return (System.nanoTime() - lastHeartbeat.get()) / 1_000_000;
    }

    public boolean isLive() {
        return millisSinceHeartbeat() < graceMillis;
    }

    public boolean isReady() {
        return isLive() && !readiness.isEmpty() && readiness.stream().allMatch(BooleanSupplier::getAsBoolean);
    }
}
