package com.blissfuljuan.dieapi.project.service;

import com.blissfuljuan.dieapi.academic.model.EnrollmentStatus;
import com.blissfuljuan.dieapi.academic.model.LockedClass;
import com.blissfuljuan.dieapi.academic.repository.ClassRepository;
import com.blissfuljuan.dieapi.account.model.AccountStatus;
import com.blissfuljuan.dieapi.account.model.UserRole;
import com.blissfuljuan.dieapi.common.exception.ApiException;
import com.blissfuljuan.dieapi.common.exception.ErrorCode;
import com.blissfuljuan.dieapi.project.model.ProjectRow;
import com.blissfuljuan.dieapi.project.repository.ProjectRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/** Input rules shared by project creation and membership changes. */
@Component
public class ProjectValidation {

    private static final int HARD_MAX_TEAM = 50;

    private final ProjectRepository repo;
    private final ClassRepository classes;

    public ProjectValidation(ProjectRepository repo, ClassRepository classes) {
        this.repo = repo;
        this.classes = classes;
    }

    /** The student must be an active, enrolled student of the class and on no other active project. */
    public void assertEnrolledAndFree(UUID classId, UUID studentId, UUID exceptProject) {
        var enrollment = classes.enrollmentStatus(classId, studentId);
        var student = classes.findStudentById(studentId);
        if (enrollment.isEmpty() || enrollment.get() != EnrollmentStatus.ENROLLED || student.isEmpty()
            || student.get().role() != UserRole.STUDENT || student.get().status() == AccountStatus.DEACTIVATED) {
            throw ApiException.badRequest(ErrorCode.STUDENT_NOT_ELIGIBLE, "Student " + studentId + " is not an active, enrolled student of this class");
        }
        var other = repo.activeProjectOf(classId, studentId);
        if (other.isPresent() && !other.get().equals(exceptProject)) {
            throw ApiException.conflict(ErrorCode.STUDENT_IN_OTHER_PROJECT, "Student " + studentId + " already belongs to another project in this class");
        }
    }

    public static void checkTeamSize(LockedClass cls, int size, boolean exception) {
        if (exception) {
            if (size < 1 || size > HARD_MAX_TEAM) {
                throw ApiException.badRequest(ErrorCode.INVALID_TEAM_SIZE, "Team size must be between 1 and " + HARD_MAX_TEAM);
            }
        } else if (size < cls.teamMinSize() || size > cls.teamMaxSize()) {
            throw ApiException.badRequest(ErrorCode.INVALID_TEAM_SIZE, "Team size must be between " + cls.teamMinSize() + " and " + cls.teamMaxSize()
                + " (set allowSizeException to override)");
        }
    }

    public static void checkVersion(ProjectRow p, long version) {
        if (p.version() != version) {
            throw ApiException.conflict(ErrorCode.PROJECT_VERSION_CONFLICT, "The project was modified by someone else; reload and retry");
        }
    }

    public static void requireVersion(Long version) {
        if (version == null) throw ApiException.badRequest(ErrorCode.VERSION_REQUIRED, "version is required");
    }

    public static List<UUID> distinct(List<UUID> ids) {
        return ids == null ? new ArrayList<>() : new ArrayList<>(new LinkedHashSet<>(ids));
    }

    public static String text(String value, String field, int max, boolean required) {
        String v = value == null || value.isBlank() ? null : value.trim();
        if (v == null) {
            if (required) throw ApiException.badRequest(ErrorCode.FIELD_REQUIRED, field + " is required");
            return null;
        }
        if (v.length() > max) throw ApiException.badRequest(ErrorCode.FIELD_TOO_LONG, field + " is too long (max " + max + ")");
        return v;
    }
}
