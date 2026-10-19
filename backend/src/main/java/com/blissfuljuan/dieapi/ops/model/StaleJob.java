package com.blissfuljuan.dieapi.ops.model;

import java.util.UUID;

/** A test job the reaper failed: its state before, and the correlation id of the request that created it. */
public record StaleJob(UUID id, TestJobState previousState, String correlationId, long ageSeconds) {}
