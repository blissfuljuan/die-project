package com.blissfuljuan.dieapi.audit.service;

import com.blissfuljuan.dieapi.audit.model.AuditEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Writes audit events to audit_log.
 *
 * Deliberately a plain synchronous {@code @EventListener}, not a {@code @TransactionalEventListener}:
 * it runs on the publisher's thread inside the publisher's transaction, so the audit row commits and
 * rolls back with the change, honours {@code noRollbackFor} flows (failed-attempt audits), and is still
 * written immediately when there is no transaction (login events). Transaction-phase listeners would
 * skip the no-transaction case and move the write to commit time.
 */
@Component
public class AuditEventListener {

    private final AuditService audit;

    public AuditEventListener(AuditService audit) {
        this.audit = audit;
    }

    @EventListener
    public void on(AuditEvent event) {
        audit.record(event.actorUserId(), event.action().name(), event.resourceType(), event.resourceId(),
            event.details());
    }
}
