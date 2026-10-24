package com.blissfuljuan.dieapi.messaging.model;

import java.util.Map;
import java.util.UUID;

/**
 * One message to put on the broker. Always persistent. {@code messageId}, {@code correlationId} and {@code type}
 * may be null for dead-lettered poison messages whose envelope could not be read.
 */
public record OutboundMessage(Destination destination, byte[] body, UUID messageId, String correlationId, String type,
                              Map<String, Object> headers) {}
