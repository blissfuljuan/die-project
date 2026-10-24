package com.blissfuljuan.dieapi.account.service.port;

import java.util.UUID;

/**
 * Port: ownership facts that account lifecycle changes must respect. Implemented by the academic module (which owns
 * workspaces), so account does not depend on it. Called synchronously inside the caller's transaction, after the
 * account row has been locked.
 */
public interface WorkspaceOwnership {

    /** Number of ACTIVE workspaces the account is the designated Owner of (archived workspaces do not count). */
    int activeWorkspacesOwnedBy(UUID userId);
}
