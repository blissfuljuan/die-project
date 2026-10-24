package com.blissfuljuan.dieapi.academic.service;

import com.blissfuljuan.dieapi.academic.model.AddCoordinator;
import com.blissfuljuan.dieapi.academic.repository.ClassRepository;
import com.blissfuljuan.dieapi.academic.view.CoordinatorCandidate;
import com.blissfuljuan.dieapi.academic.view.CoordinatorEntry;
import com.blissfuljuan.dieapi.access.model.WorkspaceCapability;
import com.blissfuljuan.dieapi.access.service.AccessPolicy;
import com.blissfuljuan.dieapi.account.model.UserRole;
import com.blissfuljuan.dieapi.audit.model.AuditAction;
import com.blissfuljuan.dieapi.audit.model.AuditEvent;
import com.blissfuljuan.dieapi.audit.model.AuditResource;
import com.blissfuljuan.dieapi.auth.model.AppUserDetails;
import com.blissfuljuan.dieapi.common.exception.ApiException;
import com.blissfuljuan.dieapi.common.exception.ErrorCode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Who administers a workspace: the single designated Owner plus other assigned coordinators with per-coordinator
 * capabilities. Every mutation takes the workspace row lock and re-authorizes under it; the Owner can never be
 * removed or have capabilities reduced, and ownership moves only by an audited transfer to an assigned coordinator,
 * so a workspace is never Owner-less (the deferred composite FK on academic_class is the database backstop).
 */
@Service
public class ClassInstructorService {

    static final int MIN_CANDIDATE_QUERY = 3;
    static final int MAX_CANDIDATES = 10;

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

    @Transactional(readOnly = true)
    public List<CoordinatorEntry> list(AppUserDetails user, UUID workspaceId) {
        policy.requireClassManage(user, workspaceId); // coordinators of the workspace and administrators
        return repo.coordinatorEntries(workspaceId);
    }

    /**
     * Picker support for "add a coordinator": active coordinator accounts that are not assigned yet, matched by the start of
     * their email or name. Only people who may add coordinators can ask, a search needs at least 3 characters and at most 10
     * matches are returned, so this cannot be used to dump the account directory.
     */
    @Transactional(readOnly = true)
    public List<CoordinatorCandidate> candidates(AppUserDetails user, UUID workspaceId, String query) {
        policy.requireWorkspaceCapability(user, workspaceId, WorkspaceCapability.MANAGE_COORDINATORS);
        String q = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        if (q.length() < MIN_CANDIDATE_QUERY) {
            return List.of();
        }
        String escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return repo.coordinatorCandidates(workspaceId, escaped, MAX_CANDIDATES);
    }

    @Transactional
    public CoordinatorEntry add(AppUserDetails user, UUID workspaceId, AddCoordinator req) {
        policy.requireWorkspaceCapability(user, workspaceId, WorkspaceCapability.MANAGE_COORDINATORS);
        locks.lockWritable(user, workspaceId, WorkspaceCapability.MANAGE_COORDINATORS);
        Set<WorkspaceCapability> capabilities = req.capabilities() == null
            ? WorkspaceCapability.DEFAULTS : parseCapabilities(req.capabilities());
        requireCanGrant(user, workspaceId, capabilities);
        requireAssignable(List.of(req.userId()));
        if (repo.isAssigned(workspaceId, req.userId())) {
            throw ApiException.conflict(ErrorCode.COORDINATOR_ALREADY_ASSIGNED, "This coordinator is already assigned to the workspace");
        }
        repo.insertCoordinator(workspaceId, req.userId(), capabilities);
        events.publishEvent(AuditEvent.of(user.getId(), AuditAction.COORDINATOR_ADDED, AuditResource.CLASS, workspaceId.toString(),
            Map.of("userId", req.userId().toString(), "capabilities", names(capabilities))));
        return repo.coordinatorEntry(workspaceId, req.userId()).orElseThrow();
    }

    @Transactional
    public CoordinatorEntry setCapabilities(AppUserDetails user, UUID workspaceId, UUID coordinatorId, List<String> requested) {
        policy.requireWorkspaceCapability(user, workspaceId, WorkspaceCapability.MANAGE_COORDINATORS);
        locks.lockWritable(user, workspaceId, WorkspaceCapability.MANAGE_COORDINATORS);
        Set<WorkspaceCapability> capabilities = parseCapabilities(requested);
        requireNotOwner(workspaceId, coordinatorId, ErrorCode.WORKSPACE_OWNER_CAPABILITIES_FIXED,
            "The Owner holds every capability, which cannot be reduced; transfer ownership first");
        CoordinatorEntry before = repo.coordinatorEntry(workspaceId, coordinatorId)
            .orElseThrow(() -> ApiException.notFound(ErrorCode.COORDINATOR_NOT_FOUND, "Coordinator not found in this workspace"));
        requireCanGrant(user, workspaceId, capabilities);
        repo.setCapabilities(workspaceId, coordinatorId, capabilities);
        events.publishEvent(AuditEvent.of(user.getId(), AuditAction.COORDINATOR_CAPABILITIES_CHANGED, AuditResource.CLASS,
            workspaceId.toString(), Map.of("userId", coordinatorId.toString(), "before", before.capabilities(),
                "after", names(capabilities))));
        return repo.coordinatorEntry(workspaceId, coordinatorId).orElseThrow();
    }

    @Transactional
    public void remove(AppUserDetails user, UUID workspaceId, UUID coordinatorId) {
        policy.requireWorkspaceCapability(user, workspaceId, WorkspaceCapability.MANAGE_COORDINATORS);
        locks.lockWritable(user, workspaceId, WorkspaceCapability.MANAGE_COORDINATORS);
        requireNotOwner(workspaceId, coordinatorId, ErrorCode.WORKSPACE_OWNER_REMOVAL_FORBIDDEN,
            "The Owner cannot be removed; transfer ownership to another coordinator first");
        if (!repo.isAssigned(workspaceId, coordinatorId)) {
            throw ApiException.notFound(ErrorCode.COORDINATOR_NOT_FOUND, "Coordinator not found in this workspace");
        }
        repo.deleteCoordinator(workspaceId, coordinatorId);
        events.publishEvent(AuditEvent.of(user.getId(), AuditAction.COORDINATOR_REMOVED, AuditResource.CLASS, workspaceId.toString(),
            Map.of("userId", coordinatorId.toString())));
    }

    /**
     * Hands ownership to an already assigned, active coordinator. Allowed to the current Owner and to administrators
     * (owner recovery), also while the workspace is archived. Sessions are not touched: authority is read per request.
     */
    @Transactional
    public void transferOwner(AppUserDetails user, UUID workspaceId, UUID newOwnerId) {
        policy.requireWorkspaceOwner(user, workspaceId);
        locks.lock(workspaceId);
        policy.requireWorkspaceOwner(user, workspaceId); // the Owner may have changed while we waited for the lock
        UUID current = repo.ownerId(workspaceId).orElseThrow(AccessPolicy::classNotFound);
        if (current.equals(newOwnerId)) {
            return; // already the Owner: nothing to do, nothing to audit
        }
        if (!repo.isAssigned(workspaceId, newOwnerId)) {
            throw ApiException.badRequest(ErrorCode.WORKSPACE_OWNER_INVALID,
                "The new Owner must already be an assigned coordinator of the workspace");
        }
        requireAssignable(List.of(newOwnerId));
        repo.updateOwner(workspaceId, newOwnerId);
        events.publishEvent(AuditEvent.of(user.getId(), AuditAction.WORKSPACE_OWNER_TRANSFERRED, AuditResource.CLASS,
            workspaceId.toString(), Map.of("from", current.toString(), "to", newOwnerId.toString())));
    }

    /** Initial assignment while creating a workspace: the Owner holds everything, the others the defaults. */
    void assignInitial(UUID workspaceId, UUID ownerId, List<UUID> coordinators) {
        for (UUID id : coordinators) {
            repo.insertCoordinator(workspaceId, id,
                id.equals(ownerId) ? WorkspaceCapability.all() : WorkspaceCapability.DEFAULTS);
        }
    }

    /** Every id must be an existing, non-deactivated coordinator account (rows are share-locked until commit). */
    void requireAssignable(List<UUID> coordinators) {
        if (repo.countAssignableInstructors(coordinators) != coordinators.size()) {
            throw ApiException.badRequest(ErrorCode.COORDINATOR_NOT_ASSIGNABLE,
                "Every coordinator id must belong to an existing, non-deactivated coordinator account");
        }
    }

    // ---- rules ----

    private void requireNotOwner(UUID workspaceId, UUID coordinatorId, ErrorCode code, String message) {
        UUID owner = repo.ownerId(workspaceId).orElseThrow(AccessPolicy::classNotFound);
        if (owner.equals(coordinatorId)) {
            throw ApiException.conflict(code, message);
        }
    }

    /** Non-administrators can only hand out capabilities they hold themselves (no escalation through delegation). */
    private void requireCanGrant(AppUserDetails user, UUID workspaceId, Collection<WorkspaceCapability> granted) {
        if (user.role() == UserRole.ADMIN) {
            return;
        }
        Set<WorkspaceCapability> mine = policy.capabilitiesOf(user, List.of(workspaceId)).get(workspaceId);
        if (!mine.containsAll(granted)) {
            throw ApiException.forbidden(ErrorCode.CAPABILITY_ESCALATION_FORBIDDEN,
                "You cannot grant capabilities that you do not hold yourself");
        }
    }

    private static Set<WorkspaceCapability> parseCapabilities(List<String> names) {
        Set<WorkspaceCapability> parsed = EnumSet.noneOf(WorkspaceCapability.class);
        if (names == null) {
            return parsed;
        }
        for (String name : names) {
            WorkspaceCapability capability = WorkspaceCapability.parse(name);
            if (capability == null) {
                throw ApiException.badRequest(ErrorCode.INVALID_CAPABILITY, "Unknown capability; valid values: "
                    + String.join(", ", Arrays.stream(WorkspaceCapability.values()).map(Enum::name).toList()));
            }
            parsed.add(capability);
        }
        return parsed;
    }

    private static List<String> names(Collection<WorkspaceCapability> capabilities) {
        return capabilities.stream().map(Enum::name).sorted().toList();
    }
}
