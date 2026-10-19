package com.blissfuljuan.dieapi.messaging.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The versioned internal message envelope (contracts/messaging/envelope-v1.schema.json, docs/messaging.md).
 *
 * @param envelopeVersion schema version, 1 today
 * @param messageId       identity of the message; kept across redelivery and retry, the consumers' dedupe key
 * @param correlationId   the originating API request's X-Request-Id
 * @param type            message type, e.g. {@code test.ping}
 * @param createdAt       UTC instant of first creation
 * @param attempt         delivery attempt, starting at 1
 * @param payload         type-specific body
 */
public record Envelope(int envelopeVersion, UUID messageId, String correlationId, String type, Instant createdAt,
                       int attempt, Map<String, Object> payload) {

    public static final int CURRENT_VERSION = 1;

    /** The same message for the next delivery attempt (identity and creation time are kept). */
    public Envelope nextAttempt() {
        return new Envelope(envelopeVersion, messageId, correlationId, type, createdAt, attempt + 1, payload);
    }
}
