package com.blissfuljuan.dieapi.academic.service;

import com.blissfuljuan.dieapi.academic.repository.ClassRepository;
import com.blissfuljuan.dieapi.access.service.AccessPolicy;
import com.blissfuljuan.dieapi.audit.model.AuditAction;
import com.blissfuljuan.dieapi.audit.model.AuditEvent;
import com.blissfuljuan.dieapi.audit.model.AuditResource;
import org.springframework.context.ApplicationEventPublisher;
import com.blissfuljuan.dieapi.auth.model.AppUserDetails;
import com.blissfuljuan.dieapi.common.exception.ApiException;
import com.blissfuljuan.dieapi.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Who teaches a class. The endpoint is admin-only (controller); AccessPolicy and archive rules still apply. */
@Service
public class ClassInstructorService {

    private final ClassRepository repo;
    private final AccessPolicy policy;
    private final ApplicationEventPublisher events;
    private final ClassLocks locks;

    public ClassInstructorService(ClassRepository repo, AccessPolicy policy, ApplicationEventPublisher events, ClassLocks locks) {
        this.repo = repo;
        this.policy = policy;
        this.events = events;
        this.locks = locks;
    }

    @Transactional
    public void setCoordinators(AppUserDetails user, UUID workspaceId, List<UUID> requested) {
        policy.requireClassManage(user, workspaceId);
        locks.lockWritable(workspaceId);
        List<UUID> coordinators = ClassInputs.distinct(requested);
        if (coordinators.isEmpty()) {
            throw ApiException.badRequest(ErrorCode.COORDINATORS_REQUIRED, "A workspace needs at least one coordinator");
        }
        requireAssignable(coordinators);
        List<UUID> before = repo.coordinatorIds(workspaceId);
        repo.replaceInstructors(workspaceId, coordinators);
        events.publishEvent(AuditEvent.of(user.getId(), AuditAction.CLASS_INSTRUCTORS_CHANGED, AuditResource.CLASS, workspaceId.toString(),
            Map.of("before", before.stream().map(UUID::toString).toList(),
                "after", coordinators.stream().map(UUID::toString).toList())));
    }

    /** Every id must be an existing, non-deactivated instructor account (also used when creating a class). */
    void requireAssignable(List<UUID> coordinators) {
        if (repo.countAssignableInstructors(coordinators) != coordinators.size()) {
            throw ApiException.badRequest(ErrorCode.COORDINATOR_NOT_ASSIGNABLE,
                "Every coordinator id must belong to an existing, non-deactivated coordinator account");
        }
    }
}
