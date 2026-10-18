package com.blissfuljuan.dieapi.academic.service;

import com.blissfuljuan.dieapi.academic.model.ClassStatus;
import com.blissfuljuan.dieapi.academic.model.LockedClass;
import com.blissfuljuan.dieapi.academic.repository.ClassRepository;
import com.blissfuljuan.dieapi.access.service.AccessPolicy;
import com.blissfuljuan.dieapi.common.exception.ApiException;
import com.blissfuljuan.dieapi.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Class row lock plus the archived-is-read-only rule; callers must already have passed AccessPolicy. */
@Component
public class ClassLocks {

    private final ClassRepository repo;

    public ClassLocks(ClassRepository repo) {
        this.repo = repo;
    }

    public LockedClass lock(UUID workspaceId) {
        return repo.lock(workspaceId).orElseThrow(AccessPolicy::classNotFound);
    }

    public LockedClass lockWritable(UUID workspaceId) {
        LockedClass cur = lock(workspaceId);
        assertWritable(cur.status());
        return cur;
    }

    public static void assertWritable(ClassStatus status) {
        if (status == ClassStatus.ARCHIVED) {
            throw ApiException.conflict(ErrorCode.CLASS_ARCHIVED, "The workspace is archived and read-only");
        }
    }
}
