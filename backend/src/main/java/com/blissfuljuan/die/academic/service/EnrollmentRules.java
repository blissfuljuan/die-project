package com.blissfuljuan.die.academic.service;

import com.blissfuljuan.die.academic.repository.ClassRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

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

    public Outcome enroll(UUID classId, UUID studentId, String program, String section) {
        var existing = repo.enrollmentStatus(classId, studentId);
        if (existing.isPresent() && "ENROLLED".equals(existing.get())) {
            return Outcome.ALREADY_ENROLLED;
        }
        if (existing.isPresent()) {
            repo.reinstateEnrollment(classId, studentId, program, section);
            return Outcome.REINSTATED;
        }
        repo.insertEnrollment(classId, studentId, program, section);
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
    public static boolean mayEnroll(boolean callerIsAdmin, UUID callerId, String studentRole, String studentStatus,
                                    UUID createdBy, boolean knownToCaller) {
        if (!"STUDENT".equals(studentRole) || "DEACTIVATED".equals(studentStatus)) return false;
        if (callerIsAdmin || "ACTIVE".equals(studentStatus)) return true;
        return knownToCaller || callerId.equals(createdBy);
    }
}
