package com.blissfuljuan.dieapi.access.service;

import com.blissfuljuan.dieapi.access.model.ClassRole;
import com.blissfuljuan.dieapi.access.model.CoordinatorGrant;
import com.blissfuljuan.dieapi.access.model.WorkspaceCapability;
import com.blissfuljuan.dieapi.access.repository.AccessRepository;
import com.blissfuljuan.dieapi.auth.model.AppUserDetails;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Resource-level capabilities and ownership of a workspace. Outsiders get the same 404 as for a missing workspace;
 * participants can see the workspace but never manage it (403); a coordinator needs the capability (the Owner
 * implicitly has all). Administrators pass only for administrative capabilities and for owner recovery; for the
 * other (content-related) capabilities they get the 404 outsiders get, exactly like the content gates.
 */
@Component
class WorkspaceCapabilityAccess {

    private final AccessRepository repo;
    private final ClassAccess classes;

    WorkspaceCapabilityAccess(AccessRepository repo, ClassAccess classes) {
        this.repo = repo;
        this.classes = classes;
    }

    ClassRole requireCapability(AppUserDetails user, UUID workspaceId, WorkspaceCapability capability) {
        ClassRole role = classes.requireView(user, workspaceId);
        switch (role) {
            case ADMIN -> {
                if (!capability.administrative()) throw ClassAccess.notFound();
            }
            case COORDINATOR -> {
                CoordinatorGrant grant = repo.findGrant(workspaceId, user.getId()).orElseThrow(ClassAccess::notFound);
                if (!grant.has(capability)) throw AccessPolicy.accessDenied();
            }
            default -> throw AccessPolicy.accessDenied();
        }
        return role;
    }

    /** The Owner of the workspace, or an administrator (owner recovery). */
    ClassRole requireOwner(AppUserDetails user, UUID workspaceId) {
        ClassRole role = classes.requireView(user, workspaceId);
        switch (role) {
            case ADMIN -> { }
            case COORDINATOR -> {
                CoordinatorGrant grant = repo.findGrant(workspaceId, user.getId()).orElseThrow(ClassAccess::notFound);
                if (!grant.owner()) throw AccessPolicy.accessDenied();
            }
            default -> throw AccessPolicy.accessDenied();
        }
        return role;
    }

    /**
     * What the caller may do in each of the workspaces, derived from the backend only: coordinators their grant
     * (Owner: everything), administrators the administrative capabilities, everyone else nothing.
     */
    Map<UUID, Set<WorkspaceCapability>> capabilitiesOf(AppUserDetails user, Collection<UUID> workspaceIds) {
        Map<UUID, Set<WorkspaceCapability>> result = new HashMap<>();
        switch (user.role()) {
            case ADMIN -> workspaceIds.forEach(id -> result.put(id, WorkspaceCapability.administrativeOnes()));
            case COORDINATOR -> repo.grantsOf(user.getId(), workspaceIds).forEach((id, g) -> result.put(id, g.effective()));
            default -> { }
        }
        workspaceIds.forEach(id -> result.putIfAbsent(id, EnumSet.noneOf(WorkspaceCapability.class)));
        return result;
    }

    /** True if the user is a coordinator of the project's workspace without MANAGE_PROJECTS (used for project staff gates). */
    boolean coordinatorLacksInProjectWorkspace(AppUserDetails user, UUID projectId, WorkspaceCapability capability) {
        return repo.workspaceOfProject(projectId)
            .flatMap(ws -> repo.findGrant(ws, user.getId()))
            .map(g -> !g.has(capability))
            .orElse(true);
    }
}
