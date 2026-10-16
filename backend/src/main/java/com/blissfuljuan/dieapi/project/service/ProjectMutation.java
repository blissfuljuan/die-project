package com.blissfuljuan.dieapi.project.service;

import com.blissfuljuan.dieapi.academic.model.ClassStatus;
import com.blissfuljuan.dieapi.academic.model.LockedClass;
import com.blissfuljuan.dieapi.academic.repository.ClassRepository;
import com.blissfuljuan.dieapi.access.model.ProjectRole;
import com.blissfuljuan.dieapi.access.service.AccessPolicy;
import com.blissfuljuan.dieapi.common.exception.ApiException;
import com.blissfuljuan.dieapi.project.model.LockedProject;
import com.blissfuljuan.dieapi.project.model.ProjectRow;
import com.blissfuljuan.dieapi.project.model.ProjectStatus;
import com.blissfuljuan.dieapi.project.repository.ProjectRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * The skeleton of every project mutation: authorize, lock the class row, lock the project row, authorize
 * again (the role may have changed while waiting for the locks), check the caller's version, then mutate.
 * Lock order is always class -> project, which serializes all team changes inside a class. Must be called
 * inside a write transaction; auditing stays with the caller's body.
 */
@Component
public class ProjectMutation {

    /** What the project's state must be once locked. */
    public enum Requires { ACTIVE_PROJECT, ANY_STATE }

    /** The mutation itself; runs with both locks held and the authorization re-checked. */
    @FunctionalInterface
    public interface Body<T> {
        T apply(LockedProject locked, ProjectRole role);
    }

    private final ProjectRepository repo;
    private final ClassRepository classes;

    public ProjectMutation(ProjectRepository repo, ClassRepository classes) {
        this.repo = repo;
        this.classes = classes;
    }

    /**
     * @param authorize runs before and after locking and returns the caller's role (throws when not allowed)
     * @param expectedVersion the caller's last-seen project version, or null when no version check applies
     */
    public <T> T run(UUID projectId, Requires requires, Supplier<ProjectRole> authorize, Long expectedVersion, Body<T> body) {
        authorize.get();
        LockedProject locked = requires == Requires.ACTIVE_PROJECT ? lockActive(projectId) : lockAnyState(projectId);
        ProjectRole role = authorize.get(); // re-check under the locks
        if (expectedVersion != null) ProjectValidation.checkVersion(locked.project(), expectedVersion);
        return body.apply(locked, role);
    }

    /** Class row first, then project row; the project must be ACTIVE in an ACTIVE class. */
    public LockedProject lockActive(UUID projectId) {
        UUID classId = repo.classIdOf(projectId).orElseThrow(AccessPolicy::projectNotFound);
        var cls = lockWritableClass(classId);
        ProjectRow p = repo.lock(projectId).orElseThrow(AccessPolicy::projectNotFound);
        if (p.status() != ProjectStatus.ACTIVE) {
            throw ApiException.conflict("PROJECT_ARCHIVED", "The project is archived and read-only");
        }
        return new LockedProject(p, cls);
    }

    /** Class and project rows with no status checks (archiving an already archived project is a no-op). */
    private LockedProject lockAnyState(UUID projectId) {
        UUID classId = repo.classIdOf(projectId).orElseThrow(AccessPolicy::projectNotFound);
        var cls = classes.lock(classId).orElseThrow(AccessPolicy::classNotFound);
        ProjectRow p = repo.lock(projectId).orElseThrow(AccessPolicy::projectNotFound);
        return new LockedProject(p, cls);
    }

    /** Like lockActive but only the class must be writable, so the caller can report precise reasons. */
    public LockedProject lockForInvitee(UUID projectId) {
        UUID classId = repo.classIdOf(projectId).orElseThrow(AccessPolicy::projectNotFound);
        var cls = classes.lock(classId).orElseThrow(AccessPolicy::classNotFound);
        ProjectRow p = repo.lock(projectId).orElseThrow(AccessPolicy::projectNotFound);
        if (cls.status() == ClassStatus.ARCHIVED) {
            throw ApiException.conflict("CLASS_ARCHIVED", "The class is archived");
        }
        return new LockedProject(p, cls);
    }

    public LockedClass lockWritableClass(UUID classId) {
        var cls = classes.lock(classId).orElseThrow(AccessPolicy::classNotFound);
        if (cls.status() == ClassStatus.ARCHIVED) {
            throw ApiException.conflict("CLASS_ARCHIVED", "The class is archived and read-only");
        }
        return cls;
    }
}
