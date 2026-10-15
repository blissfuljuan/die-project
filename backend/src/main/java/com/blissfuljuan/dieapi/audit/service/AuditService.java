package com.blissfuljuan.dieapi.audit.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/** Writes to the append-only audit_log. Call for every security- or academically-significant action. */
@Service
public class AuditService {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public AuditService(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void record(UUID actorUserId, String action, String resourceType, String resourceId,
                       Map<String, Object> details) {
        String json;
        try {
            json = mapper.writeValueAsString(details == null ? Map.of() : details);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Audit details not serializable", e);
        }
        jdbc.sql("""
                INSERT INTO audit_log (actor_user_id, action, resource_type, resource_id, details)
                VALUES (:actor, :action, :type, :rid, CAST(:details AS jsonb))
                """)
            .param("actor", actorUserId)
            .param("action", action)
            .param("type", resourceType)
            .param("rid", resourceId)
            .param("details", json)
            .update();
    }
}
