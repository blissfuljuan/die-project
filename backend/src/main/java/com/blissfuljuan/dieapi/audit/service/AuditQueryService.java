package com.blissfuljuan.dieapi.audit.service;

import com.blissfuljuan.dieapi.audit.model.AuditAction;
import com.blissfuljuan.dieapi.audit.model.AuditResource;
import com.blissfuljuan.dieapi.audit.repository.AuditLogRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Read-only counting questions other modules may ask of the audit trail ("how often did this actor do X lately"), used for
 * rate limits on sensitive actions. The trail is append-only and shared by every instance, so such limits hold across
 * API instances without extra state. Writing still happens only through audit events.
 */
@Service
public class AuditQueryService {

    private final AuditLogRepository log;

    public AuditQueryService(AuditLogRepository log) {
        this.log = log;
    }

    public long countByActor(UUID actorUserId, AuditAction action, int withinMinutes) {
        return log.countByActorSince(actorUserId, action.name(), withinMinutes);
    }

    public long countForResource(AuditAction action, AuditResource resource, String resourceId, int withinMinutes) {
        return log.countByResourceSince(action.name(), resource.value(), resourceId, withinMinutes);
    }
}
