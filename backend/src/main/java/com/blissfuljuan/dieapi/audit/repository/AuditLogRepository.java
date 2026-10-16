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
}
