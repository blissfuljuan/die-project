package com.blissfuljuan.dieapi.academic.service;

import com.blissfuljuan.dieapi.academic.model.ClassStatus;
import com.blissfuljuan.dieapi.academic.model.LockedClass;
import com.blissfuljuan.dieapi.academic.repository.ClassRepository;
import com.blissfuljuan.dieapi.access.model.WorkspaceCapability;
import com.blissfuljuan.dieapi.access.service.AccessPolicy;
import com.blissfuljuan.dieapi.auth.model.AppUserDetails;
import com.blissfuljuan.dieapi.common.exception.ApiException;
import com.blissfuljuan.dieapi.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Class row lock plus the archived-is-read-only rule. The overloads taking a user and a capability re-authorize
 * AFTER the lock is held (a capability removed or an ownership change committed while we waited for the lock takes
 * effect); callers still do the cheap authorization first so outsiders never queue for the lock.
 */
@Component
public class ClassLocks {

    private final ClassRepository repo;
    private final AccessPolicy policy;

    public ClassLocks(ClassRepository repo, AccessPolicy policy) {
        this.repo = repo;
        this.policy = policy;
    }

    public LockedClass lock(UUID workspaceId) {
        return repo.lock(workspaceId).orElseThrow(AccessPolicy::classNotFound);
    }

    public LockedClass lockWritable(UUID workspaceId) {
        LockedClass cur = lock(workspaceId);
        assertWritable(cur.status());
        return cur;
    }

    /** Lock, then re-check that the caller still holds the capability. */
    public LockedClass lock(AppUserDetails user, UUID workspaceId, WorkspaceCapability capability) {
        LockedClass cur = lock(workspaceId);
        policy.requireWorkspaceCapability(user, workspaceId, capability);
        return cur;
    }

    /** Lock, re-check the capability, then refuse archived workspaces. */
    public LockedClass lockWritable(AppUserDetails user, UUID workspaceId, WorkspaceCapability capability) {
        LockedClass cur = lock(user, workspaceId, capability);
        assertWritable(cur.status());
        return cur;
    }

    public static void assertWritable(ClassStatus status) {
        if (status == ClassStatus.ARCHIVED) {
            throw ApiException.conflict(ErrorCode.CLASS_ARCHIVED, "The workspace is archived and read-only");
        }
    }
}
