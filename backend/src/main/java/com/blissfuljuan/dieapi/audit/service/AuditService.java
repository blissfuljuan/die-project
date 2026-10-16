package com.blissfuljuan.dieapi.audit.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.blissfuljuan.dieapi.audit.repository.AuditLogRepository;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/** Writes to the append-only audit_log. Call for every security- or academically-significant action. */
@Service
public class AuditService {

    private final AuditLogRepository repository;
    private final ObjectMapper mapper;

    public AuditService(AuditLogRepository repository, ObjectMapper mapper) {
        this.repository = repository;
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
        repository.insert(actorUserId, action, resourceType, resourceId, json);
    }
}
