package com.blissfuljuan.dieapi.messaging.model;

import java.util.UUID;

/** A claimed outbox row. {@code payloadJson} is the complete envelope; {@code id} equals the envelope's messageId. */
public record OutboxEvent(UUID id, String aggregateType, String aggregateId, String exchange, String routingKey,
                          String payloadJson, int attempts, String correlationId, String messageType) {

    public Destination destination() {
        return new Destination(exchange, routingKey);
    }
}
