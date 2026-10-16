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
 * @param resourceType stored resource_type, e.g. "class", "user", "project", "auth"
 * @param resourceId   stored resource_id, may be null
 * @param details      stored JSON details, null is stored as an empty object
 */
public record AuditEvent(UUID actorUserId, AuditAction action, String resourceType, String resourceId,
                         Map<String, Object> details) {

    public static AuditEvent of(UUID actorUserId, AuditAction action, String resourceType, String resourceId,
                                Map<String, Object> details) {
        return new AuditEvent(actorUserId, action, resourceType, resourceId, details);
    }
}
