package com.blissfuljuan.dieapi.academic.service;

import com.blissfuljuan.dieapi.academic.repository.ClassRepository;
import com.blissfuljuan.dieapi.access.service.AccessPolicy;
import com.blissfuljuan.dieapi.audit.model.AuditAction;
import com.blissfuljuan.dieapi.audit.model.AuditEvent;
import org.springframework.context.ApplicationEventPublisher;
import com.blissfuljuan.dieapi.auth.model.AppUserDetails;
import com.blissfuljuan.dieapi.common.exception.ApiException;
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
    public void setInstructors(AppUserDetails user, UUID classId, List<UUID> requested) {
        policy.requireClassManage(user, classId);
        locks.lockWritable(classId);
        List<UUID> instructors = ClassInputs.distinct(requested);
        if (instructors.isEmpty()) {
            throw ApiException.badRequest("INSTRUCTORS_REQUIRED", "A class needs at least one instructor");
        }
        requireAssignable(instructors);
        List<UUID> before = repo.instructorIds(classId);
        repo.replaceInstructors(classId, instructors);
        events.publishEvent(AuditEvent.of(user.getId(), AuditAction.CLASS_INSTRUCTORS_CHANGED, "class", classId.toString(),
            Map.of("before", before.stream().map(UUID::toString).toList(),
                "after", instructors.stream().map(UUID::toString).toList())));
    }

    /** Every id must be an existing, non-deactivated instructor account (also used when creating a class). */
    void requireAssignable(List<UUID> instructors) {
        if (repo.countAssignableInstructors(instructors) != instructors.size()) {
            throw ApiException.badRequest("INSTRUCTOR_NOT_ASSIGNABLE",
                "Every instructor id must belong to an existing, non-deactivated instructor account");
        }
    }
}
