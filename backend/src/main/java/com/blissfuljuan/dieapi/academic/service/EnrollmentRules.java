package com.blissfuljuan.dieapi.academic.service;

import com.blissfuljuan.dieapi.academic.model.EnrollmentStatus;
import com.blissfuljuan.dieapi.academic.repository.ClassRepository;
import com.blissfuljuan.dieapi.account.model.AccountStatus;
import com.blissfuljuan.dieapi.account.model.UserRole;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The one definition of how a student becomes enrolled in a class. Used by manual enrollment and by
 * roster import so both behave identically: an existing ENROLLED row is a duplicate, a REMOVED row is
 * reinstated, otherwise a new row is created. Callers must already have passed AccessPolicy and hold
 * the class row lock.
 */
@Component
public class EnrollmentRules {

    public enum Outcome { ENROLLED, REINSTATED, ALREADY_ENROLLED }

    private final ClassRepository repo;

    public EnrollmentRules(ClassRepository repo) {
        this.repo = repo;
    }

    public Outcome enroll(UUID workspaceId, UUID participantId, String program, String section) {
        var existing = repo.enrollmentStatus(workspaceId, participantId);
        if (existing.isPresent() && existing.get() == EnrollmentStatus.ENROLLED) {
            return Outcome.ALREADY_ENROLLED;
        }
        if (existing.isPresent()) {
            repo.reinstateEnrollment(workspaceId, participantId, program, section);
            return Outcome.REINSTATED;
        }
        repo.insertEnrollment(workspaceId, participantId, program, section);
        return Outcome.ENROLLED;
    }

    /**
     * Who may place a student in a class.
     *  - Only real, non-deactivated student accounts, ever.
     *  - Administrators: any such student.
     *  - Instructors: students who activated their own account (they hold their own credentials), plus
     *    never-activated students the instructor created or already teaches. Claiming somebody else's
     *    pending account would let the instructor re-issue its temporary credential and take it over.
     */
    public static boolean mayEnroll(boolean callerIsAdmin, UUID callerId, UserRole studentRole, AccountStatus studentStatus,
                                    UUID createdBy, boolean knownToCaller) {
        if (studentRole != UserRole.PARTICIPANT || studentStatus == AccountStatus.DEACTIVATED) return false;
        if (callerIsAdmin || studentStatus == AccountStatus.ACTIVE) return true;
        return knownToCaller || callerId.equals(createdBy);
    }
}
