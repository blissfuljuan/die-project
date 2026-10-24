package com.blissfuljuan.dieapi.ops.view;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** A test job as clients see it. */
public record TestJobView(UUID id, String state, String note, boolean simulateFailure, String correlationId,
                          Map<String, Object> result, String failureReason, Instant createdAt, Instant startedAt,
                          Instant finishedAt) {}
