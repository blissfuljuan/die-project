package com.blissfuljuan.dieapi.ops.model;

/**
 * What the API learned about one worker from its health endpoint. {@code state}: UP (ready, heartbeat fresh), DOWN
 * (answered but not ready or heartbeat stale), UNREACHABLE (no answer), NOT_CONFIGURED (no URL set).
 * {@code heartbeatAgeSeconds} is null when the worker did not report one.
 */
public record WorkerHealth(String name, State state, Double heartbeatAgeSeconds) {

    public enum State { UP, DOWN, UNREACHABLE, NOT_CONFIGURED }
}
