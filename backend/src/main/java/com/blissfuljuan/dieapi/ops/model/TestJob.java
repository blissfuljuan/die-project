package com.blissfuljuan.dieapi.ops.model;

import java.time.Instant;
import java.util.UUID;

/** A test_job row. {@code resultJson} is the worker chain's result document (null until finished). */
public record TestJob(UUID id, TestJobState state, String note, boolean simulateFailure, UUID requestedBy,
                      String correlationId, String resultJson, String failureReason, Instant createdAt,
                      Instant startedAt, Instant finishedAt) {}
