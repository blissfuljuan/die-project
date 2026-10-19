package com.blissfuljuan.dieapi.messaging.service;

import com.blissfuljuan.dieapi.messaging.model.Destination;
import com.blissfuljuan.dieapi.messaging.model.Envelope;
import com.blissfuljuan.dieapi.messaging.repository.OutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/**
 * Transactional outbox, write side. Callers insert the outbox row in the SAME transaction as their state change
 * (propagation MANDATORY enforces it); {@link OutboxRelay} publishes it afterwards. Nothing talks to the broker here.
 */
@Service
public class OutboxService {

    private final OutboxRepository outbox;
    private final EnvelopeCodec codec;

    public OutboxService(OutboxRepository outbox, EnvelopeCodec codec) {
        this.outbox = outbox;
        this.codec = codec;
    }

    /** @return the new message's id (envelope messageId == outbox row id) */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID enqueue(String aggregateType, String aggregateId, Destination destination, String type,
                        String correlationId, Map<String, Object> payload) {
        UUID id = UUID.randomUUID();
        Envelope envelope = new Envelope(Envelope.CURRENT_VERSION, id, correlationId, type,
            Instant.now().truncatedTo(ChronoUnit.MILLIS), 1, payload);
        outbox.insert(id, aggregateType, aggregateId, destination.exchange(), destination.routingKey(), type,
            correlationId, codec.encode(envelope));
        return id;
    }
}
