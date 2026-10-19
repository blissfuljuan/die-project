package com.blissfuljuan.dieapi.ops.model;

/** Counts of test jobs by state, plus jobs that are unfinished and older than the stale threshold. */
public record JobStats(long queued, long running, long succeeded, long failed, long overdue) {}
