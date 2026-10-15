package com.blissfuljuan.dieapi.academic.service;

import com.blissfuljuan.dieapi.academic.model.LockedClass;
import com.blissfuljuan.dieapi.academic.model.NewClass;
import com.blissfuljuan.dieapi.academic.model.StudentRef;
import com.blissfuljuan.dieapi.academic.repository.ClassRepository;
import com.blissfuljuan.dieapi.academic.view.ClassPage;
import com.blissfuljuan.dieapi.academic.view.ClassView;
import com.blissfuljuan.dieapi.academic.view.EnrollmentView;
import com.blissfuljuan.dieapi.academic.view.TemporaryCredentialView;
import com.blissfuljuan.dieapi.access.service.AccessPolicy;
import com.blissfuljuan.dieapi.account.service.TempCredentialService;
import com.blissfuljuan.dieapi.audit.service.AuditService;
import com.blissfuljuan.dieapi.auth.model.AppUserDetails;
import com.blissfuljuan.dieapi.project.repository.ProjectRepository;
import com.blissfuljuan.dieapi.project.service.TeamMembership;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Class lifecycle, instructor assignment and manual enrollment. Every operation first asks the
 * AccessPolicy; archived classes are read-only.
 */
@Service
public class ClassService {

    public record CreateClass(String academicYear, String semester, String subjectCode, String subjectName,
                              String section, String teamFormation, Integer teamMinSize, Integer teamMaxSize,
                              Boolean studentsMayChangeTeams, List<UUID> instructorIds) {}

    public record UpdateClass(Long version, String academicYear, String semester, String subjectCode,
                              String subjectName, String section, String teamFormation, Integer teamMinSize,
                              Integer teamMaxSize, Boolean studentsMayChangeTeams) {}

    public record Enroll(UUID studentId, String studentNumber, String lastName, String program, String section) {}

    private static final Set<String> FORMATIONS = Set.of("STUDENT_FORMED", "INSTRUCTOR_ASSIGNED");

    private final ClassRepository repo;
    private final AccessPolicy policy;
    private final AuditService audit;
    private final EnrollmentRules enrollmentRules;
    private final TempCredentialService tempCredentials;
    private final TeamMembership teamMembership;
    private final ProjectRepository projectRepository;

    public ClassService(ClassRepository repo, AccessPolicy policy, AuditService audit,
                        EnrollmentRules enrollmentRules, TempCredentialService tempCredentials,
                        TeamMembership teamMembership, ProjectRepository projectRepository) {
        this.repo = repo;
        this.policy = policy;
        this.audit = audit;
        this.enrollmentRules = enrollmentRules;
        this.tempCredentials = tempCredentials;
        this.teamMembership = teamMembership;
        this.projectRepository = projectRepository;
    }

    // ---- queries ----

    @Transactional(readOnly = true)
    public ClassPage list(AppUserDetails user, String status, String q, int page, int size) {
        if (status != null && !Set.of("ACTIVE", "ARCHIVED").contains(status)) {
            throw bad("status must be ACTIVE or ARCHIVED");
        }
        return repo.search(policy.visibleClasses(user), status, q, Math.max(page, 0), Math.min(Math.max(size, 1), 100));
    }

    @Transactional(readOnly = true)
    public ClassView get(AppUserDetails user, UUID classId) {
        policy.requireClassView(user, classId);
        return repo.find(classId).orElseThrow(AccessPolicy::classNotFound);
    }

    @Transactional(readOnly = true)
    public List<EnrollmentView> roster(AppUserDetails user, UUID classId, boolean includeRemoved) {
        policy.requireClassManage(user, classId);
        return repo.enrollments(classId, includeRemoved);
    }

    // ---- class lifecycle ----

    @Transactional
    public ClassView create(AppUserDetails user, CreateClass req) {
        List<UUID> instructors;
        if ("ADMIN".equals(user.getRole())) {
            instructors = distinct(req.instructorIds());
            if (instructors.isEmpty()) {
                throw bad("instructorIds must list at least one instructor");
            }
        } else {
            instructors = List.of(user.getId()); // an instructor creating a class teaches it
        }
        assertAssignable(instructors);

        NewClass fields = fields(
            required(req.academicYear(), "academicYear"), required(req.semester(), "semester"),
            required(req.subjectCode(), "subjectCode"), required(req.subjectName(), "subjectName"),
            required(req.section(), "section"),
            req.teamFormation() == null ? "INSTRUCTOR_ASSIGNED" : req.teamFormation(),
            req.teamMinSize() == null ? 1 : req.teamMinSize(),
            req.teamMaxSize() == null ? 5 : req.teamMaxSize(),
            Boolean.TRUE.equals(req.studentsMayChangeTeams()), user.getId());

        UUID id = repo.insert(fields);
        repo.replaceInstructors(id, instructors);
        audit.record(user.getId(), "CLASS_CREATED", "class", id.toString(), Map.of("instructors", instructors.size()));
        return repo.find(id).orElseThrow();
    }

    @Transactional
    public ClassView update(AppUserDetails user, UUID classId, UpdateClass req) {
        if (req.version() == null) throw bad("version is required");
        policy.requireClassManage(user, classId);
        LockedClass cur = repo.lock(classId).orElseThrow(AccessPolicy::classNotFound);
        assertWritable(cur.status());
        if (cur.version() != req.version()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The class was modified by someone else; reload and retry");
        }
        NewClass next = fields(
            pick(req.academicYear(), cur.academicYear(), "academicYear"), pick(req.semester(), cur.semester(), "semester"),
            pick(req.subjectCode(), cur.subjectCode(), "subjectCode"), pick(req.subjectName(), cur.subjectName(), "subjectName"),
            pick(req.section(), cur.section(), "section"),
            req.teamFormation() == null ? cur.teamFormation() : req.teamFormation(),
            req.teamMinSize() == null ? cur.teamMinSize() : req.teamMinSize(),
            req.teamMaxSize() == null ? cur.teamMaxSize() : req.teamMaxSize(),
            req.studentsMayChangeTeams() == null ? cur.studentsMayChangeTeams() : req.studentsMayChangeTeams(),
            null);
        // Team-policy changes must not silently invalidate teams that already exist.
        int largestTeam = projectRepository.maxActiveTeamSize(classId);
        if (next.teamMaxSize() < largestTeam) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An existing team has " + largestTeam
                + " members, which exceeds the new maximum of " + next.teamMaxSize() + "; adjust that team first");
        }
        repo.update(classId, next);
        audit.record(user.getId(), "CLASS_UPDATED", "class", classId.toString(), Map.of());
        return repo.find(classId).orElseThrow();
    }

    @Transactional
    public ClassView archive(AppUserDetails user, UUID classId) {
        policy.requireClassManage(user, classId);
        LockedClass cur = repo.lock(classId).orElseThrow(AccessPolicy::classNotFound);
        if (!"ARCHIVED".equals(cur.status())) {
            repo.setStatus(classId, "ARCHIVED");
            audit.record(user.getId(), "CLASS_ARCHIVED", "class", classId.toString(), Map.of());
        }
        return repo.find(classId).orElseThrow();
    }

    /** Admin only (enforced on the controller). */
    @Transactional
    public ClassView unarchive(AppUserDetails user, UUID classId) {
        policy.requireClassView(user, classId);
        LockedClass cur = repo.lock(classId).orElseThrow(AccessPolicy::classNotFound);
        if ("ARCHIVED".equals(cur.status())) {
            repo.setStatus(classId, "ACTIVE");
            audit.record(user.getId(), "CLASS_UNARCHIVED", "class", classId.toString(), Map.of());
        }
        return repo.find(classId).orElseThrow();
    }

    @Transactional
    public void setInstructors(AppUserDetails user, UUID classId, List<UUID> requested) {
        policy.requireClassManage(user, classId);
        LockedClass cur = repo.lock(classId).orElseThrow(AccessPolicy::classNotFound);
        assertWritable(cur.status());
        List<UUID> instructors = distinct(requested);
        if (instructors.isEmpty()) {
            throw bad("A class needs at least one instructor");
        }
        assertAssignable(instructors);
        List<UUID> before = repo.instructorIds(classId);
        repo.replaceInstructors(classId, instructors);
        audit.record(user.getId(), "CLASS_INSTRUCTORS_CHANGED", "class", classId.toString(),
            Map.of("before", before.stream().map(UUID::toString).toList(),
                "after", instructors.stream().map(UUID::toString).toList()));
    }

    // ---- enrollment ----

    @Transactional
    public void enroll(AppUserDetails user, UUID classId, Enroll req) {
        policy.requireClassManage(user, classId);
        LockedClass cur = repo.lock(classId).orElseThrow(AccessPolicy::classNotFound);
        assertWritable(cur.status());
        if ((req.studentId() == null) == (req.studentNumber() == null || req.studentNumber().isBlank())) {
            throw bad("Provide exactly one of studentId or studentNumber");
        }
        boolean admin = "ADMIN".equals(user.getRole());
        boolean byNumber = req.studentId() == null;
        if (byNumber && !admin && (req.lastName() == null || req.lastName().isBlank())) {
            // Instructors must prove they know the person, not just guess student numbers.
            throw bad("lastName is required when enrolling by studentNumber");
        }
        // Every reason for refusing (unknown, deactivated, not a student, an account the caller may not claim,
        // wrong last name) answers with the same 404, so nothing is learned about other classes' accounts.
        StudentRef student = (byNumber
            ? repo.findStudentByNumber(req.studentNumber().trim())
            : repo.findStudentById(req.studentId()))
            .filter(s -> EnrollmentRules.mayEnroll(admin, user.getId(), s.role(), s.status(), s.createdBy(),
                !admin && "PENDING_ACTIVATION".equals(s.status()) && repo.studentKnownToInstructor(user.getId(), s.id())))
            .filter(s -> !byNumber || admin || sameText(s.lastName(), req.lastName()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Student not found"));

        var outcome = enrollmentRules.enroll(classId, student.id(), blankToNull(req.program()), blankToNull(req.section()));
        if (outcome == EnrollmentRules.Outcome.ALREADY_ENROLLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Student is already enrolled in this class");
        }
        audit.record(user.getId(), "STUDENT_ENROLLED", "class", classId.toString(),
            Map.of("studentId", student.id().toString(), "reinstated", outcome == EnrollmentRules.Outcome.REINSTATED));
    }

    /**
     * Re-issues the temporary credential of an enrolled student who has never activated the account
     * (e.g. the import result sheet was lost). The previous credential stops working. Never applies to
     * accounts that have been activated: those use password reset.
     */
    @Transactional
    public TemporaryCredentialView reissueTemporaryCredential(AppUserDetails user, UUID classId, UUID studentId) {
        policy.requireClassManage(user, classId);
        LockedClass cur = repo.lock(classId).orElseThrow(AccessPolicy::classNotFound);
        assertWritable(cur.status());
        var enrollment = repo.enrollmentStatus(classId, studentId);
        var student = repo.findStudentById(studentId);
        if (enrollment.isEmpty() || !"ENROLLED".equals(enrollment.get()) || student.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Enrollment not found");
        }
        if (!"PENDING_ACTIVATION".equals(student.get().status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "The account is already activated; use password reset instead");
        }
        var issued = tempCredentials.issue(studentId, user.getId());
        audit.record(user.getId(), "TEMP_CREDENTIAL_REISSUED", "user", studentId.toString(),
            Map.of("classId", classId.toString()));
        return new TemporaryCredentialView(issued.secret(), issued.expiresAt());
    }

    @Transactional
    public void unenroll(AppUserDetails user, UUID classId, UUID studentId) {
        policy.requireClassManage(user, classId);
        LockedClass cur = repo.lock(classId).orElseThrow(AccessPolicy::classNotFound);
        assertWritable(cur.status());
        var existing = repo.enrollmentStatus(classId, studentId);
        if (existing.isEmpty() || !"ENROLLED".equals(existing.get())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Enrollment not found");
        }
        repo.removeEnrollment(classId, studentId);
        // A student who is no longer enrolled cannot stay on a team or hold team invitations.
        teamMembership.onStudentRemovedFromClass(classId, studentId, user.getId());
        audit.record(user.getId(), "STUDENT_REMOVED", "class", classId.toString(), Map.of("studentId", studentId.toString()));
    }

    // ---- helpers ----

    private void assertAssignable(List<UUID> instructors) {
        if (repo.countAssignableInstructors(instructors) != instructors.size()) {
            throw bad("Every instructor id must belong to an existing, non-deactivated instructor account");
        }
    }

    private static void assertWritable(String status) {
        if ("ARCHIVED".equals(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The class is archived and read-only");
        }
    }

    private static NewClass fields(String year, String semester, String code, String name,
                                                   String section, String formation, int min, int max,
                                                   boolean mayChange, UUID createdBy) {
        if (!FORMATIONS.contains(formation)) throw bad("teamFormation must be STUDENT_FORMED or INSTRUCTOR_ASSIGNED");
        if (min < 1 || max < min || max > 50) throw bad("Team sizes must satisfy 1 <= min <= max <= 50");
        return new NewClass(year, semester, code, name, section, formation, min, max, mayChange, createdBy);
    }

    private static List<UUID> distinct(List<UUID> ids) {
        return ids == null ? new ArrayList<>() : new ArrayList<>(new LinkedHashSet<>(ids));
    }

    private static String pick(String requested, String current, String field) {
        return requested == null ? current : required(requested, field);
    }

    private static String required(String value, String field) {
        String v = blankToNull(value);
        if (v == null) throw bad(field + " is required");
        if (v.length() > 100) throw bad(field + " is too long");
        return v;
    }

    private static boolean sameText(String a, String b) {
        return a != null && b != null && a.strip().equalsIgnoreCase(b.strip());
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static ResponseStatusException bad(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
