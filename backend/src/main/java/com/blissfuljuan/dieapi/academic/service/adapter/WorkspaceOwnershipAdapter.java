package com.blissfuljuan.dieapi.academic.service.adapter;

import com.blissfuljuan.dieapi.academic.repository.ClassRepository;
import com.blissfuljuan.dieapi.account.service.port.WorkspaceOwnership;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Adapter: the academic module's implementation of the account module's {@link WorkspaceOwnership} port. */
@Component
public class WorkspaceOwnershipAdapter implements WorkspaceOwnership {

    private final ClassRepository classes;

    public WorkspaceOwnershipAdapter(ClassRepository classes) {
        this.classes = classes;
    }

    @Override
    public int activeWorkspacesOwnedBy(UUID userId) {
        return classes.countActiveOwnedBy(userId);
    }
}
