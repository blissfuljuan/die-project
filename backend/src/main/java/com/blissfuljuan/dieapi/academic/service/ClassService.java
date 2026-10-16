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
import com.blissfuljuan.dieapi.access.service.AccessPolicy;
import com.blissfuljuan.dieapi.account.model.UserRole;
import com.blissfuljuan.dieapi.audit.model.AuditAction;
import com.blissfuljuan.dieapi.audit.model.AuditEvent;
import org.springframework.context.ApplicationEventPublisher;
import com.blissfuljuan.dieapi.auth.model.AppUserDetails;
import com.blissfuljuan.dieapi.common.exception.ApiException;
import com.blissfuljuan.dieapi.project.repository.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
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
    private final ClassInstructorService instructors;
    private final ProjectRepository projectRepository;

    public ClassService(ClassRepository repo, AccessPolicy policy, ApplicationEventPublisher events, ClassLocks locks,
                        ClassInstructorService instructors, ProjectRepository projectRepository) {
        this.repo = repo;
        this.policy = policy;
        this.events = events;
        this.locks = locks;
        this.instructors = instructors;
        this.projectRepository = projectRepository;
    }

    // ---- queries ----

    @Transactional(readOnly = true)
    public ClassPage list(AppUserDetails user, String status, String q, int page, int size) {
        ClassStatus statusFilter = null;
        if (status != null) {
            statusFilter = ClassStatus.parse(status);
            if (statusFilter == null) {
                throw ApiException.badRequest("INVALID_STATUS_FILTER", "status must be ACTIVE or ARCHIVED");
            }
        }
        return repo.search(policy.visibleClasses(user), statusFilter, q, Math.max(page, 0), Math.min(Math.max(size, 1), 100));
    }

    @Transactional(readOnly = true)
    public ClassView get(AppUserDetails user, UUID classId) {
        policy.requireClassView(user, classId);
        return repo.find(classId).orElseThrow(AccessPolicy::classNotFound);
    }

    // ---- class lifecycle ----

    @Transactional
    public ClassView create(AppUserDetails user, CreateClass req) {
        List<UUID> teaching;
        if (user.role() == UserRole.ADMIN) {
            teaching = distinct(req.instructorIds());
            if (teaching.isEmpty()) {
                throw ApiException.badRequest("INSTRUCTORS_REQUIRED", "instructorIds must list at least one instructor");
            }
        } else {
            teaching = List.of(user.getId()); // an instructor creating a class teaches it
        }
        instructors.requireAssignable(teaching);

        NewClass fields = fields(
            required(req.academicYear(), "academicYear"), required(req.semester(), "semester"),
            required(req.subjectCode(), "subjectCode"), required(req.subjectName(), "subjectName"),
            required(req.section(), "section"),
            req.teamFormation() == null ? TeamFormation.INSTRUCTOR_ASSIGNED.name() : req.teamFormation(),
            req.teamMinSize() == null ? 1 : req.teamMinSize(),
            req.teamMaxSize() == null ? 5 : req.teamMaxSize(),
            Boolean.TRUE.equals(req.studentsMayChangeTeams()), user.getId());

        UUID id = repo.insert(fields);
        repo.replaceInstructors(id, teaching);
        events.publishEvent(AuditEvent.of(user.getId(), AuditAction.CLASS_CREATED, "class", id.toString(), Map.of("instructors", teaching.size())));
        return repo.find(id).orElseThrow();
    }

    @Transactional
    public ClassView update(AppUserDetails user, UUID classId, UpdateClass req) {
        if (req.version() == null) throw ApiException.badRequest("VERSION_REQUIRED", "version is required");
        policy.requireClassManage(user, classId);
        LockedClass cur = locks.lockWritable(classId);
        if (cur.version() != req.version()) {
            throw ApiException.conflict("CLASS_VERSION_CONFLICT", "The class was modified by someone else; reload and retry");
        }
        NewClass next = fields(
            pick(req.academicYear(), cur.academicYear(), "academicYear"), pick(req.semester(), cur.semester(), "semester"),
            pick(req.subjectCode(), cur.subjectCode(), "subjectCode"), pick(req.subjectName(), cur.subjectName(), "subjectName"),
            pick(req.section(), cur.section(), "section"),
            req.teamFormation() == null ? cur.teamFormation().name() : req.teamFormation(),
            req.teamMinSize() == null ? cur.teamMinSize() : req.teamMinSize(),
            req.teamMaxSize() == null ? cur.teamMaxSize() : req.teamMaxSize(),
            req.studentsMayChangeTeams() == null ? cur.studentsMayChangeTeams() : req.studentsMayChangeTeams(),
            null);
        // Team-policy changes must not silently invalidate teams that already exist.
        int largestTeam = projectRepository.maxActiveTeamSize(classId);
        if (next.teamMaxSize() < largestTeam) {
            throw ApiException.conflict("TEAM_SIZE_BELOW_EXISTING_TEAM", "An existing team has " + largestTeam
                + " members, which exceeds the new maximum of " + next.teamMaxSize() + "; adjust that team first");
        }
        repo.update(classId, next);
        events.publishEvent(AuditEvent.of(user.getId(), AuditAction.CLASS_UPDATED, "class", classId.toString(), Map.of()));
        return repo.find(classId).orElseThrow();
    }

    @Transactional
    public ClassView archive(AppUserDetails user, UUID classId) {
        policy.requireClassManage(user, classId);
        LockedClass cur = locks.lock(classId);
        if (cur.status() != ClassStatus.ARCHIVED) {
            repo.setStatus(classId, ClassStatus.ARCHIVED);
            events.publishEvent(AuditEvent.of(user.getId(), AuditAction.CLASS_ARCHIVED, "class", classId.toString(), Map.of()));
        }
        return repo.find(classId).orElseThrow();
    }

    /** Admin only (enforced on the controller). */
    @Transactional
    public ClassView unarchive(AppUserDetails user, UUID classId) {
        policy.requireClassView(user, classId);
        LockedClass cur = locks.lock(classId);
        if (cur.status() == ClassStatus.ARCHIVED) {
            repo.setStatus(classId, ClassStatus.ACTIVE);
            events.publishEvent(AuditEvent.of(user.getId(), AuditAction.CLASS_UNARCHIVED, "class", classId.toString(), Map.of()));
        }
        return repo.find(classId).orElseThrow();
    }

    private static NewClass fields(String year, String semester, String code, String name,
                                   String section, String formation, int min, int max,
                                   boolean mayChange, UUID createdBy) {
        TeamFormation parsedFormation = TeamFormation.parse(formation);
        if (parsedFormation == null) {
            throw ApiException.badRequest("INVALID_TEAM_FORMATION", "teamFormation must be STUDENT_FORMED or INSTRUCTOR_ASSIGNED");
        }
        if (min < 1 || max < min || max > 50) {
            throw ApiException.badRequest("INVALID_TEAM_SIZE", "Team sizes must satisfy 1 <= min <= max <= 50");
        }
        return new NewClass(year, semester, code, name, section, parsedFormation, min, max, mayChange, createdBy);
    }
}
