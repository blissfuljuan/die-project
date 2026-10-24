package com.blissfuljuan.die.orchestrator;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/** The versioned message envelope (contracts/messaging/envelope-v1.schema.json, docs/messaging.md). */
public record Envelope(int envelopeVersion, String messageId, String correlationId, String type, String createdAt,
                       int attempt, Map<String, Object> payload) {

    public Envelope nextAttempt() {
        return new Envelope(envelopeVersion, messageId, correlationId, type, createdAt, attempt + 1, payload);
    }

    /** A new message derived from this one. Its id is a function of (this id, step), so a redelivered input produces
     *  output that downstream consumers recognise as a duplicate. */
    public Envelope derive(String step, String newType, Map<String, Object> newPayload) {
        String id = UUID.nameUUIDFromBytes((messageId + "|" + step).getBytes(StandardCharsets.UTF_8)).toString();
        return new Envelope(1, id, correlationId, newType, Instant.now().truncatedTo(ChronoUnit.MILLIS).toString(), 1,
            newPayload);
    }
}
