package com.blissfuljuan.dieapi.academic.controller;

import com.blissfuljuan.dieapi.academic.model.AddCoordinator;
import com.blissfuljuan.dieapi.academic.model.CreateClass;
import com.blissfuljuan.dieapi.academic.model.EnrollStudent;
import com.blissfuljuan.dieapi.academic.model.UpdateClass;
import com.blissfuljuan.dieapi.academic.service.ClassEnrollmentService;
import com.blissfuljuan.dieapi.academic.service.ClassInstructorService;
import com.blissfuljuan.dieapi.academic.service.ClassService;
import com.blissfuljuan.dieapi.academic.view.ClassPage;
import com.blissfuljuan.dieapi.academic.view.ClassView;
import com.blissfuljuan.dieapi.academic.view.CoordinatorEntry;
import com.blissfuljuan.dieapi.academic.view.EnrollmentView;
import com.blissfuljuan.dieapi.academic.view.TemporaryCredentialView;
import com.blissfuljuan.dieapi.auth.model.AppUserDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Classes and enrollments. Role gates here are coarse; the fine-grained decision (admin, assigned
 * instructor, enrolled student) is always made by AccessPolicy inside the class services.
 */
@RestController
@RequestMapping("/api/v1/workspaces")
public class ClassController {

    public record CreateClassRequest(
            @Size(max = 100) String academicYear, @Size(max = 100) String semester,
            @Size(max = 100) String subjectCode, @Size(max = 100) String subjectName,
            @Size(max = 100) String section, String teamFormation, Integer teamMinSize, Integer teamMaxSize,
            Boolean participantsMayChangeTeams, List<UUID> coordinatorIds, UUID ownerId) {}

    public record UpdateClassRequest(
        @NotNull Long version,
        @Size(max = 100) String academicYear, @Size(max = 100) String semester,
        @Size(max = 100) String subjectCode, @Size(max = 100) String subjectName,
        @Size(max = 100) String section, String teamFormation, Integer teamMinSize, Integer teamMaxSize,
        Boolean participantsMayChangeTeams) {}

    /** capabilities omitted = the defaults (MANAGE_ROSTER, MANAGE_PROJECTS). */
    public record AddCoordinatorRequest(@NotNull UUID userId, List<String> capabilities) {}

    public record CapabilitiesRequest(@NotNull List<String> capabilities) {}

    public record TransferOwnerRequest(@NotNull UUID userId) {}

    /** lastName is required for coordinators enrolling by studentNumber (admins may omit it). */
    public record EnrollRequest(UUID participantId, @Size(max = 64) String studentNumber, @Size(max = 100) String lastName,
                                @Size(max = 100) String program, @Size(max = 100) String section) {}

    private final ClassService classes;
    private final ClassInstructorService coordinators;
    private final ClassEnrollmentService enrollments;

    public ClassController(ClassService classes, ClassInstructorService coordinators, ClassEnrollmentService enrollments) {
        this.classes = classes;
        this.coordinators = coordinators;
        this.enrollments = enrollments;
    }

    @GetMapping
    public ClassPage list(@AuthenticationPrincipal AppUserDetails user,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) String q,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "25") int size) {
        return classes.list(user, status, q, page, size);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','COORDINATOR')")
    public ResponseEntity<ClassView> create(@AuthenticationPrincipal AppUserDetails user,
                                            @Valid @RequestBody CreateClassRequest body) {
        ClassView created = classes.create(user, new CreateClass(body.academicYear(), body.semester(),
            body.subjectCode(), body.subjectName(), body.section(), body.teamFormation(), body.teamMinSize(),
            body.teamMaxSize(), body.participantsMayChangeTeams(), body.coordinatorIds(), body.ownerId()));
        return ResponseEntity.created(URI.create("/api/v1/workspaces/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    public ClassView get(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id) {
        return classes.get(user, id);
    }

    @PatchMapping("/{id}")
    public ClassView update(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id,
                            @Valid @RequestBody UpdateClassRequest body) {
        return classes.update(user, id, new UpdateClass(body.version(), body.academicYear(),
            body.semester(), body.subjectCode(), body.subjectName(), body.section(), body.teamFormation(),
            body.teamMinSize(), body.teamMaxSize(), body.participantsMayChangeTeams()));
    }

    @PostMapping("/{id}/archive")
    public ClassView archive(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id) {
        return classes.archive(user, id);
    }

    @PostMapping("/{id}/unarchive")
    @PreAuthorize("hasRole('ADMIN')")
    public ClassView unarchive(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id) {
        return classes.unarchive(user, id);
    }

    /** Assigned coordinators with their effective capabilities; coordinators of the workspace and administrators only. */
    @GetMapping("/{id}/coordinators")
    public List<CoordinatorEntry> listCoordinators(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id) {
        return coordinators.list(user, id);
    }

    /** Owner, a coordinator with MANAGE_COORDINATORS, or an administrator; the target must be an active coordinator account. */
    @PostMapping("/{id}/coordinators")
    public ResponseEntity<CoordinatorEntry> addCoordinator(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id,
                                                           @Valid @RequestBody AddCoordinatorRequest body) {
        CoordinatorEntry added = coordinators.add(user, id, new AddCoordinator(body.userId(), body.capabilities()));
        return ResponseEntity.created(URI.create("/api/v1/workspaces/" + id + "/coordinators/" + added.userId())).body(added);
    }

    /** The Owner's capabilities cannot be changed (409). */
    @PatchMapping("/{id}/coordinators/{userId}")
    public CoordinatorEntry setCapabilities(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id,
                                            @PathVariable UUID userId, @Valid @RequestBody CapabilitiesRequest body) {
        return coordinators.setCapabilities(user, id, userId, body.capabilities());
    }

    /** The Owner cannot be removed (409): transfer ownership first. */
    @DeleteMapping("/{id}/coordinators/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeCoordinator(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id, @PathVariable UUID userId) {
        coordinators.remove(user, id, userId);
    }

    /** Ownership transfer: current Owner or an administrator; the new Owner must already be an assigned coordinator. */
    @PutMapping("/{id}/owner")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void transferOwner(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id,
                              @Valid @RequestBody TransferOwnerRequest body) {
        coordinators.transferOwner(user, id, body.userId());
    }

    @GetMapping("/{id}/enrollments")
    public List<EnrollmentView> roster(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id,
                                       @RequestParam(defaultValue = "false") boolean includeRemoved) {
        return enrollments.roster(user, id, includeRemoved);
    }

    @PostMapping("/{id}/enrollments")
    @ResponseStatus(HttpStatus.CREATED)
    public void enroll(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id,
                       @Valid @RequestBody EnrollRequest body) {
        enrollments.enroll(user, id, new EnrollStudent(body.participantId(), body.studentNumber(), body.lastName(),
            body.program(), body.section()));
    }

    /** Staff of the class only; for students who have not activated their account yet. Returned once. */
    @PostMapping("/{id}/enrollments/{participantId}/temporary-credential")
    public ResponseEntity<TemporaryCredentialView> reissueTemporaryCredential(
            @AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id, @PathVariable UUID participantId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(enrollments.reissueTemporaryCredential(user, id, participantId));
    }

    @DeleteMapping("/{id}/enrollments/{participantId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unenroll(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id,
                         @PathVariable UUID participantId) {
        enrollments.unenroll(user, id, participantId);
    }
}
