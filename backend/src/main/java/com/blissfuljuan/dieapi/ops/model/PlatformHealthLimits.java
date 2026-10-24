package com.blissfuljuan.dieapi.ops.model;

/** Thresholds the admin health report applies. */
public record PlatformHealthLimits(double maxHeartbeatAgeSeconds, long staleJobSeconds) {}
