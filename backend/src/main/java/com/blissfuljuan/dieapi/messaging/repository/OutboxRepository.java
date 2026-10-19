package com.blissfuljuan.dieapi.messaging.repository;

import com.blissfuljuan.dieapi.messaging.model.OutboxEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** All SQL of the transactional outbox (table outbox_event). */
@Repository
public class OutboxRepository {

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, String aggregateType, String aggregateId, String exchange, String routingKey,
                       String messageType, String correlationId, String envelopeJson) {
        jdbc.sql("""
                INSERT INTO outbox_event (id, aggregate_type, aggregate_id, exchange, routing_key, message_type,
                                          correlation_id, payload)
                VALUES (:id, :at, :aid, :ex, :rk, :mt, :cid, CAST(:payload AS jsonb))
                """)
            .param("id", id).param("at", aggregateType).param("aid", aggregateId).param("ex", exchange)
            .param("rk", routingKey).param("mt", messageType).param("cid", correlationId)
            .param("payload", envelopeJson)
            .update();
    }

    /** A claimed row together with its creation time (used only to restore publication order). */
    private record Claimed(Timestamp createdAt, OutboxEvent event) {}

    /**
     * Leases up to {@code limit} due rows for {@code leaseSeconds}. Short transaction: the broker call happens after
     * it, so no row lock is held during network I/O. A relay that dies before marking a row loses the lease when it
     * expires and the row is published again (consumers dedupe on messageId).
     */
    public List<OutboxEvent> claimDue(int limit, int leaseSeconds) {
        return jdbc.sql("""
                UPDATE outbox_event SET locked_until = now() + make_interval(secs => :lease)
                WHERE id IN (
                    SELECT id FROM outbox_event
                    WHERE status = 'PENDING' AND next_attempt_at <= now()
                      AND (locked_until IS NULL OR locked_until < now())
                    ORDER BY created_at
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED)
                RETURNING id, created_at, aggregate_type, aggregate_id, exchange, routing_key, payload::text AS payload,
                          attempts, correlation_id, message_type
                """)
            .param("lease", leaseSeconds).param("limit", limit)
            .query((rs, n) -> new Claimed(rs.getTimestamp("created_at"),
                new OutboxEvent(rs.getObject("id", UUID.class), rs.getString("aggregate_type"),
                    rs.getString("aggregate_id"), rs.getString("exchange"), rs.getString("routing_key"),
                    rs.getString("payload"), rs.getInt("attempts"), rs.getString("correlation_id"),
                    rs.getString("message_type"))))
            .list().stream()
            .sorted(Comparator.comparing(Claimed::createdAt))
            .map(Claimed::event)
            .toList();
    }

    public void markPublished(UUID id) {
        jdbc.sql("""
                UPDATE outbox_event SET status = 'PUBLISHED', published_at = now(), locked_until = NULL,
                       last_error = NULL, attempts = attempts + 1
                WHERE id = :id
                """).param("id", id).update();
    }

    /** Records a failed publication and schedules the next one; {@code giveUp} parks the row as FAILED for an operator. */
    public void markFailed(UUID id, String error, int backoffSeconds, boolean giveUp) {
        jdbc.sql("""
                UPDATE outbox_event SET attempts = attempts + 1, last_error = :err, locked_until = NULL,
                       next_attempt_at = now() + make_interval(secs => :backoff),
                       status = CASE WHEN :giveUp THEN 'FAILED' ELSE status END
                WHERE id = :id
                """)
            .param("id", id).param("err", error).param("backoff", backoffSeconds).param("giveUp", giveUp)
            .update();
    }
}
