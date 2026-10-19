package com.blissfuljuan.dieapi.access.model;

import java.util.Set;

/** What one assigned coordinator holds in one workspace: the Owner flag and the stored capabilities. */
public record CoordinatorGrant(boolean owner, Set<WorkspaceCapability> stored) {

    /** The Owner implicitly holds every capability; everyone else only what was granted. */
    public boolean has(WorkspaceCapability capability) {
        return owner || stored.contains(capability);
    }

    public Set<WorkspaceCapability> effective() {
        return owner ? WorkspaceCapability.all() : stored;
    }
}
