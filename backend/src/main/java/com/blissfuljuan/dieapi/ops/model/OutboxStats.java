package com.blissfuljuan.dieapi.ops.model;

/** Outbox backlog. {@code oldestPendingSeconds} is null when nothing is pending. */
public record OutboxStats(long pending, long failed, Long oldestPendingSeconds) {}
