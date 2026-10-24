package com.blissfuljuan.dieapi.academic.service;

import com.blissfuljuan.dieapi.academic.model.ClassStatus;
import com.blissfuljuan.dieapi.academic.model.CreateClass;
import com.blissfuljuan.dieapi.academic.model.LockedClass;
import com.blissfuljuan.dieapi.academic.model.NewClass;
import com.blissfuljuan.dieapi.academic.model.TeamFormation;
import com.blissfuljuan.dieapi.academic.model.UpdateClass;
import com.blissfuljuan.dieapi.academic.repository.ClassRepository;
import com.blissfuljuan.dieapi.academic.view.ClassPage;
import com.blissfuljuan.dieapi.academic.view.ClassView;
import com.blissfuljuan.dieapi.access.model.WorkspaceCapability;
import com.blissfuljuan.dieapi.access.service.AccessPolicy;
import com.blissfuljuan.dieapi.account.model.UserRole;
import com.blissfuljuan.dieapi.audit.model.AuditAction;
import com.blissfuljuan.dieapi.audit.model.AuditEvent;
import com.blissfuljuan.dieapi.audit.model.AuditResource;
import org.springframework.context.ApplicationEventPublisher;
import com.blissfuljuan.dieapi.auth.model.AppUserDetails;
import com.blissfuljuan.dieapi.common.exception.ApiException;
import com.blissfuljuan.dieapi.common.exception.ErrorCode;
import com.blissfuljuan.dieapi.academic.service.port.ClassTeamStats;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.blissfuljuan.dieapi.academic.service.ClassInputs.distinct;
import static com.blissfuljuan.dieapi.academic.service.ClassInputs.pick;
import static com.blissfuljuan.dieapi.academic.service.ClassInputs.required;

/**
 * Class lifecycle: list/get, create, update, archive/unarchive. Every operation first asks the
 * AccessPolicy; archived classes are read-only. Instructors and enrollment have their own services.
 */
@Service
public class ClassService {

    private final ClassRepository repo;
    private final AccessPolicy policy;
    private final ApplicationEventPublisher events;
    private final ClassLocks locks;
    private final ClassInstructorService coordinators;
    private final ClassTeamStats teamStats;

    public ClassService(ClassRepository repo, AccessPolicy policy, ApplicationEventPublisher events, ClassLocks locks,
                        ClassInstructorService coordinators, ClassTeamStats teamStats) {
        this.repo = repo;
        this.policy = policy;
        this.events = events;
        this.locks = locks;
        this.coordinators = coordinators;
        this.teamStats = teamStats;
    }

    // ---- queries ----

    @Transactional(readOnly = true)
    public ClassPage list(AppUserDetails user, String status, String q, int page, int size) {
        ClassStatus statusFilter = null;
        if (status != null) {
            statusFilter = ClassStatus.parse(status);
            if (statusFilter == null) {
                throw ApiException.badRequest(ErrorCode.INVALID_STATUS_FILTER, "status must be ACTIVE or ARCHIVED");
            }
        }
        ClassPage found = repo.search(policy.visibleClasses(user), statusFilter, q, Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        Map<UUID, Set<WorkspaceCapability>> mine = policy.capabilitiesOf(user, found.items().stream().map(ClassView::id).toList());
        return new ClassPage(found.items().stream().map(v -> v.withMyCapabilities(names(mine.get(v.id())))).toList(),
            found.page(), found.size(), found.total());
    }

    @Transactional(readOnly = true)
    public ClassView get(AppUserDetails user, UUID workspaceId) {
        policy.requireClassView(user, workspaceId);
        return forCaller(user, repo.find(workspaceId).orElseThrow(AccessPolicy::classNotFound));
    }

    // ---- class lifecycle ----

    @Transactional
    public ClassView create(AppUserDetails user, CreateClass req) {
        // Every active workspace has exactly one designated Owner, who is also an assigned coordinator.
        UUID owner;
        List<UUID> teaching;
        if (user.role() == UserRole.ADMIN) {
            owner = req.ownerId();
            if (owner == null) {
                throw ApiException.badRequest(ErrorCode.WORKSPACE_OWNER_REQUIRED, "ownerId is required: name the coordinator who will own the workspace");
            }
            teaching = distinct(req.coordinatorIds());
            if (!teaching.contains(owner)) {
                teaching.add(0, owner); // the Owner is always among the assigned coordinators
            }
        } else {
            owner = user.getId(); // a coordinator creating a workspace owns it
            if (req.ownerId() != null && !req.ownerId().equals(owner)) {
                throw ApiException.badRequest(ErrorCode.WORKSPACE_OWNER_INVALID, "A coordinator who creates a workspace becomes its Owner");
            }
            teaching = new ArrayList<>(List.of(owner));
        }
        coordinators.requireAssignable(teaching);

        NewClass fields = fields(
            required(req.academicYear(), "academicYear"), required(req.semester(), "semester"),
            required(req.subjectCode(), "subjectCode"), required(req.subjectName(), "subjectName"),
            required(req.section(), "section"),
            req.teamFormation() == null ? TeamFormation.COORDINATOR_ASSIGNED.name() : req.teamFormation(),
            req.teamMinSize() == null ? 1 : req.teamMinSize(),
            req.teamMaxSize() == null ? 5 : req.teamMaxSize(),
            Boolean.TRUE.equals(req.participantsMayChangeTeams()), user.getId());

        UUID id = repo.insert(fields, owner);
        coordinators.assignInitial(id, owner, teaching);
        events.publishEvent(AuditEvent.of(user.getId(), AuditAction.CLASS_CREATED, AuditResource.CLASS, id.toString(),
            Map.of("coordinators", teaching.size(), "ownerId", owner.toString())));
        return forCaller(user, repo.find(id).orElseThrow());
    }

    @Transactional
    public ClassView update(AppUserDetails user, UUID workspaceId, UpdateClass req) {
        if (req.version() == null) throw ApiException.badRequest(ErrorCode.VERSION_REQUIRED, "version is required");
        policy.requireWorkspaceCapability(user, workspaceId, WorkspaceCapability.MANAGE_SETTINGS);
        LockedClass cur = locks.lockWritable(user, workspaceId, WorkspaceCapability.MANAGE_SETTINGS);
        if (cur.version() != req.version()) {
            throw ApiException.conflict(ErrorCode.CLASS_VERSION_CONFLICT, "The workspace was modified by someone else; reload and retry");
        }
        NewClass next = fields(
            pick(req.academicYear(), cur.academicYear(), "academicYear"), pick(req.semester(), cur.semester(), "semester"),
            pick(req.subjectCode(), cur.subjectCode(), "subjectCode"), pick(req.subjectName(), cur.subjectName(), "subjectName"),
            pick(req.section(), cur.section(), "section"),
            req.teamFormation() == null ? cur.teamFormation().name() : req.teamFormation(),
            req.teamMinSize() == null ? cur.teamMinSize() : req.teamMinSize(),
            req.teamMaxSize() == null ? cur.teamMaxSize() : req.teamMaxSize(),
            req.participantsMayChangeTeams() == null ? cur.participantsMayChangeTeams() : req.participantsMayChangeTeams(),
            null);
        // Team-policy changes must not silently invalidate teams that already exist.
        int largestTeam = teamStats.largestActiveTeamSize(workspaceId);
        if (next.teamMaxSize() < largestTeam) {
            throw ApiException.conflict(ErrorCode.TEAM_SIZE_BELOW_EXISTING_TEAM, "An existing team has " + largestTeam
                + " members, which exceeds the new maximum of " + next.teamMaxSize() + "; adjust that team first");
        }
        repo.update(workspaceId, next);
        events.publishEvent(AuditEvent.of(user.getId(), AuditAction.CLASS_UPDATED, AuditResource.CLASS, workspaceId.toString(), Map.of()));
        return forCaller(user, repo.find(workspaceId).orElseThrow());
    }

    @Transactional
    public ClassView archive(AppUserDetails user, UUID workspaceId) {
        policy.requireWorkspaceCapability(user, workspaceId, WorkspaceCapability.MANAGE_SETTINGS);
        LockedClass cur = locks.lock(user, workspaceId, WorkspaceCapability.MANAGE_SETTINGS);
        if (cur.status() != ClassStatus.ARCHIVED) {
            repo.setStatus(workspaceId, ClassStatus.ARCHIVED);
            events.publishEvent(AuditEvent.of(user.getId(), AuditAction.CLASS_ARCHIVED, AuditResource.CLASS, workspaceId.toString(), Map.of()));
        }
        return forCaller(user, repo.find(workspaceId).orElseThrow());
    }

    /** Admin only (enforced on the controller). */
    @Transactional
    public ClassView unarchive(AppUserDetails user, UUID workspaceId) {
        policy.requireClassView(user, workspaceId);
        LockedClass cur = locks.lock(workspaceId);
        if (cur.status() == ClassStatus.ARCHIVED) {
            // An active workspace must have a usable Owner: an archived workspace whose Owner was deactivated meanwhile
            // needs an ownership transfer (administrators can do it while archived) before it can be reactivated.
            if (!repo.ownerIsActiveCoordinator(workspaceId)) {
                throw ApiException.conflict(ErrorCode.WORKSPACE_OWNER_INACTIVE,
                    "The workspace's Owner is no longer an active coordinator; transfer ownership before unarchiving");
            }
            repo.setStatus(workspaceId, ClassStatus.ACTIVE);
            events.publishEvent(AuditEvent.of(user.getId(), AuditAction.CLASS_UNARCHIVED, AuditResource.CLASS, workspaceId.toString(), Map.of()));
        }
        return forCaller(user, repo.find(workspaceId).orElseThrow());
    }

    /** Adds the capabilities the backend derives for this caller; the frontend never computes them. */
    private ClassView forCaller(AppUserDetails user, ClassView view) {
        return view.withMyCapabilities(names(policy.capabilitiesOf(user, List.of(view.id())).get(view.id())));
    }

    private static List<String> names(Set<WorkspaceCapability> capabilities) {
        return Arrays.stream(WorkspaceCapability.values()).filter(capabilities::contains).map(Enum::name).toList();
    }

    private static NewClass fields(String year, String semester, String code, String name,
                                   String section, String formation, int min, int max,
                                   boolean mayChange, UUID createdBy) {
        TeamFormation parsedFormation = TeamFormation.parse(formation);
        if (parsedFormation == null) {
            throw ApiException.badRequest(ErrorCode.INVALID_TEAM_FORMATION, "teamFormation must be PARTICIPANT_FORMED or COORDINATOR_ASSIGNED");
        }
        if (min < 1 || max < min || max > 50) {
            throw ApiException.badRequest(ErrorCode.INVALID_TEAM_SIZE, "Team sizes must satisfy 1 <= min <= max <= 50");
        }
        return new NewClass(year, semester, code, name, section, parsedFormation, min, max, mayChange, createdBy);
    }
}
