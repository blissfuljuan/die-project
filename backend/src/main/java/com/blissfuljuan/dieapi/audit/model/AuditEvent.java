package com.blissfuljuan.dieapi.audit.model;

import java.util.Map;
import java.util.UUID;

/**
 * An auditable fact, published with ApplicationEventPublisher and written to audit_log by
 * {@code AuditEventListener}. The listener runs synchronously on the publishing thread, so the row
 * joins the publisher's transaction (or is written on its own when there is none).
 *
 * @param actorUserId  who did it; null for anonymous/system actions
 * @param action       what happened (the enum name is the stored action string)
 * @param resource     what kind of resource it concerns (stored as its lowercase value in resource_type)
 * @param resourceId   stored resource_id, may be null
 * @param details      stored JSON details, null is stored as an empty object
 */
public record AuditEvent(UUID actorUserId, AuditAction action, AuditResource resource, String resourceId,
                         Map<String, Object> details) {

    public static AuditEvent of(UUID actorUserId, AuditAction action, AuditResource resource, String resourceId,
                                Map<String, Object> details) {
        return new AuditEvent(actorUserId, action, resource, resourceId, details);
    }

    /** Convenience for callers that only have the stored string ("class", "user", "project", "auth"). */
    public static AuditEvent of(UUID actorUserId, AuditAction action, String resourceType, String resourceId,
                                Map<String, Object> details) {
        return new AuditEvent(actorUserId, action, AuditResource.fromValue(resourceType), resourceId, details);
    }
}
