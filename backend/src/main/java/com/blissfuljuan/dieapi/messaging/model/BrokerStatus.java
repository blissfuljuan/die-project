package com.blissfuljuan.dieapi.messaging.model;

/** Result of one broker connectivity check. {@code reason} is a short, internals-free phrase when the broker is down. */
public record BrokerStatus(boolean up, String reason, long checkMillis) {}
