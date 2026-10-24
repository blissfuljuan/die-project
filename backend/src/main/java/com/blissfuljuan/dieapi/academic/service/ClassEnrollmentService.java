package com.blissfuljuan.dieapi.academic.service;

import com.blissfuljuan.dieapi.academic.model.EnrollStudent;
import com.blissfuljuan.dieapi.academic.model.EnrollmentStatus;
import com.blissfuljuan.dieapi.academic.model.StudentRef;
import com.blissfuljuan.dieapi.academic.repository.ClassRepository;
import com.blissfuljuan.dieapi.academic.view.EnrollmentPage;
import com.blissfuljuan.dieapi.academic.view.TemporaryCredentialView;
import com.blissfuljuan.dieapi.access.model.WorkspaceCapability;
import com.blissfuljuan.dieapi.access.service.AccessPolicy;
import com.blissfuljuan.dieapi.account.model.AccountStatus;
import com.blissfuljuan.dieapi.account.model.UserRole;
import com.blissfuljuan.dieapi.account.service.TempCredentialService;
import com.blissfuljuan.dieapi.audit.model.AuditAction;
import com.blissfuljuan.dieapi.audit.model.AuditEvent;
import com.blissfuljuan.dieapi.audit.model.AuditResource;
import com.blissfuljuan.dieapi.audit.service.AuditQueryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import com.blissfuljuan.dieapi.auth.model.AppUserDetails;
import com.blissfuljuan.dieapi.common.exception.ApiException;
import com.blissfuljuan.dieapi.common.exception.ErrorCode;
import com.blissfuljuan.dieapi.academic.service.port.StudentRemovalHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.blissfuljuan.dieapi.academic.service.ClassInputs.blankToNull;
import static com.blissfuljuan.dieapi.academic.service.ClassInputs.sameText;

/** Roster, manual enrollment, removal and temporary-credential re-issue for a class. */
@Service
public class ClassEnrollmentService {

    /** Largest page size the roster endpoint serves; a roster import is capped at 500 rows, so 200 keeps pages small. */
    static final int MAX_ROSTER_PAGE = 200;

    private final ClassRepository repo;
    private final AccessPolicy policy;
    private final ApplicationEventPublisher events;
    private final ClassLocks locks;
    private final EnrollmentRules enrollmentRules;
    private final TempCredentialService tempCredentials;
    private final List<StudentRemovalHandler> removalHandlers;
    private final AuditQueryService auditTrail;
    private final int reissuePerCoordinatorPerHour;
    private final int reissuePerParticipantPerHour;

    public ClassEnrollmentService(ClassRepository repo, AccessPolicy policy, ApplicationEventPublisher events, ClassLocks locks,
                                  EnrollmentRules enrollmentRules, TempCredentialService tempCredentials,
                                  List<StudentRemovalHandler> removalHandlers, AuditQueryService auditTrail,
                                  @Value("${die.roster.reissue-per-coordinator-per-hour:30}") int reissuePerCoordinatorPerHour,
                                  @Value("${die.roster.reissue-per-participant-per-hour:3}") int reissuePerParticipantPerHour) {
        this.repo = repo;
        this.policy = policy;
        this.events = events;
        this.locks = locks;
        this.enrollmentRules = enrollmentRules;
        this.tempCredentials = tempCredentials;
        this.removalHandlers = removalHandlers;
        this.auditTrail = auditTrail;
        this.reissuePerCoordinatorPerHour = reissuePerCoordinatorPerHour;
        this.reissuePerParticipantPerHour = reissuePerParticipantPerHour;
    }

    @Transactional(readOnly = true)
    public EnrollmentPage roster(AppUserDetails user, UUID workspaceId, boolean includeRemoved, int page, int size) {
        policy.requireWorkspaceCapability(user, workspaceId, WorkspaceCapability.MANAGE_ROSTER);
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), MAX_ROSTER_PAGE);
        return new EnrollmentPage(repo.enrollments(workspaceId, includeRemoved, s, p * s), p, s, repo.countEnrollments(workspaceId, includeRemoved));
    }

    @Transactional
    public void enroll(AppUserDetails user, UUID workspaceId, EnrollStudent req) {
        policy.requireWorkspaceCapability(user, workspaceId, WorkspaceCapability.MANAGE_ROSTER);
        locks.lockWritable(user, workspaceId, WorkspaceCapability.MANAGE_ROSTER);
        if ((req.participantId() == null) == (req.studentNumber() == null || req.studentNumber().isBlank())) {
            throw ApiException.badRequest(ErrorCode.INVALID_ENROLLMENT_TARGET, "Provide exactly one of participantId or studentNumber");
        }
        boolean admin = user.role() == UserRole.ADMIN;
        boolean byNumber = req.participantId() == null;
        if (byNumber && !admin && (req.lastName() == null || req.lastName().isBlank())) {
            // Instructors must prove they know the person, not just guess student numbers.
            throw ApiException.badRequest(ErrorCode.LAST_NAME_REQUIRED, "lastName is required when enrolling by studentNumber");
        }
        // Every reason for refusing (unknown, deactivated, not a student, an account the caller may not claim,
        // wrong last name) answers with the same 404, so nothing is learned about other classes' accounts.
        StudentRef student = (byNumber
            ? repo.findStudentByNumber(req.studentNumber().trim())
            : repo.findStudentById(req.participantId()))
            .filter(s -> EnrollmentRules.mayEnroll(admin, user.getId(), s.role(), s.status(), s.createdBy(),
                !admin && s.status() == AccountStatus.PENDING_ACTIVATION && repo.studentKnownToInstructor(user.getId(), s.id())))
            .filter(s -> !byNumber || admin || sameText(s.lastName(), req.lastName()))
            .orElseThrow(() -> ApiException.notFound(ErrorCode.PARTICIPANT_NOT_FOUND, "Participant not found"));

        var outcome = enrollmentRules.enroll(workspaceId, student.id(), blankToNull(req.program()), blankToNull(req.section()));
        if (outcome == EnrollmentRules.Outcome.ALREADY_ENROLLED) {
            throw ApiException.conflict(ErrorCode.ALREADY_ENROLLED, "Participant is already enrolled in this workspace");
        }
        events.publishEvent(AuditEvent.of(user.getId(), AuditAction.STUDENT_ENROLLED, AuditResource.CLASS, workspaceId.toString(),
            Map.of("participantId", student.id().toString(), "reinstated", outcome == EnrollmentRules.Outcome.REINSTATED)));
    }

    /**
     * Re-issues the temporary credential of an enrolled student who has never activated the account
     * (e.g. the import result sheet was lost). The previous credential stops working. Never applies to
     * accounts that have been activated: those use password reset.
     */
    @Transactional
    public TemporaryCredentialView reissueTemporaryCredential(AppUserDetails user, UUID workspaceId, UUID participantId) {
        policy.requireWorkspaceCapability(user, workspaceId, WorkspaceCapability.MANAGE_ROSTER);
        locks.lockWritable(user, workspaceId, WorkspaceCapability.MANAGE_ROSTER);
        var enrollment = repo.enrollmentStatus(workspaceId, participantId);
        var student = repo.findStudentById(participantId);
        if (enrollment.isEmpty() || enrollment.get() != EnrollmentStatus.ENROLLED || student.isEmpty()) {
            throw ApiException.notFound(ErrorCode.ENROLLMENT_NOT_FOUND, "Enrollment not found");
        }
        if (student.get().status() != AccountStatus.PENDING_ACTIVATION) {
            throw ApiException.conflict(ErrorCode.ACCOUNT_ALREADY_ACTIVATED,
                "The account is already activated; use password reset instead");
        }
        // Re-issuing hands out a working secret for someone else's account: cap how often one coordinator can do it
        // and how often it can be done for one participant (counted from the audit trail, so it holds across instances).
        if (auditTrail.countByActor(user.getId(), AuditAction.TEMP_CREDENTIAL_REISSUED, 60) >= reissuePerCoordinatorPerHour
            || auditTrail.countForResource(AuditAction.TEMP_CREDENTIAL_REISSUED, AuditResource.USER, participantId.toString(), 60) >= reissuePerParticipantPerHour) {
            throw ApiException.tooManyRequests(ErrorCode.CREDENTIAL_REISSUE_LIMIT,
                "Too many temporary credentials were issued recently. Wait a while before issuing another.");
        }
        var issued = tempCredentials.issue(participantId, user.getId());
        events.publishEvent(AuditEvent.of(user.getId(), AuditAction.TEMP_CREDENTIAL_REISSUED, AuditResource.USER, participantId.toString(),
            Map.of("workspaceId", workspaceId.toString())));
        return new TemporaryCredentialView(issued.secret(), issued.expiresAt());
    }

    @Transactional
    public void unenroll(AppUserDetails user, UUID workspaceId, UUID participantId) {
        policy.requireWorkspaceCapability(user, workspaceId, WorkspaceCapability.MANAGE_ROSTER);
        locks.lockWritable(user, workspaceId, WorkspaceCapability.MANAGE_ROSTER);
        var existing = repo.enrollmentStatus(workspaceId, participantId);
        if (existing.isEmpty() || existing.get() != EnrollmentStatus.ENROLLED) {
            throw ApiException.notFound(ErrorCode.ENROLLMENT_NOT_FOUND, "Enrollment not found");
        }
        repo.removeEnrollment(workspaceId, participantId);
        // A student who is no longer enrolled cannot stay on a team or hold team invitations.
        removalHandlers.forEach(h -> h.onStudentRemovedFromClass(workspaceId, participantId, user.getId()));
        events.publishEvent(AuditEvent.of(user.getId(), AuditAction.STUDENT_REMOVED, AuditResource.CLASS, workspaceId.toString(), Map.of("participantId", participantId.toString())));
    }
}
