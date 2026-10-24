package com.blissfuljuan.dieapi.audit.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** Append-only persistence of audit_log. */
@Repository
public class AuditLogRepository {

    private final JdbcClient jdbc;

    public AuditLogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID actorUserId, String action, String resourceType, String resourceId, String detailsJson) {
        jdbc.sql("""
                INSERT INTO audit_log (actor_user_id, action, resource_type, resource_id, details)
                VALUES (:actor, :action, :type, :rid, CAST(:details AS jsonb))
                """)
            .param("actor", actorUserId)
            .param("action", action)
            .param("type", resourceType)
            .param("rid", resourceId)
            .param("details", detailsJson)
            .update();
    }

    /** How many times this actor did {@code action} in the last {@code minutes} (database clock). */
    public long countByActorSince(UUID actorUserId, String action, int minutes) {
        return jdbc.sql("""
                SELECT count(*) FROM audit_log
                WHERE actor_user_id = :actor AND action = :action AND occurred_at > now() - make_interval(mins => :m)
                """)
            .param("actor", actorUserId).param("action", action).param("m", minutes)
            .query(Long.class).single();
    }

    /** How many times {@code action} was recorded for one resource in the last {@code minutes}. */
    public long countByResourceSince(String action, String resourceType, String resourceId, int minutes) {
        return jdbc.sql("""
                SELECT count(*) FROM audit_log
                WHERE action = :action AND resource_type = :type AND resource_id = :rid
                  AND occurred_at > now() - make_interval(mins => :m)
                """)
            .param("action", action).param("type", resourceType).param("rid", resourceId).param("m", minutes)
            .query(Long.class).single();
    }
}
