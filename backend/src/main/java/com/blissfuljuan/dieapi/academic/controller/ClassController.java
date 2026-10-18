package com.blissfuljuan.dieapi.academic.controller;

import com.blissfuljuan.dieapi.academic.model.CreateClass;
import com.blissfuljuan.dieapi.academic.model.EnrollStudent;
import com.blissfuljuan.dieapi.academic.model.UpdateClass;
import com.blissfuljuan.dieapi.academic.service.ClassEnrollmentService;
import com.blissfuljuan.dieapi.academic.service.ClassInstructorService;
import com.blissfuljuan.dieapi.academic.service.ClassService;
import com.blissfuljuan.dieapi.academic.view.ClassPage;
import com.blissfuljuan.dieapi.academic.view.ClassView;
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
            Boolean participantsMayChangeTeams, List<UUID> coordinatorIds) {}

    public record UpdateClassRequest(
        @NotNull Long version,
        @Size(max = 100) String academicYear, @Size(max = 100) String semester,
        @Size(max = 100) String subjectCode, @Size(max = 100) String subjectName,
        @Size(max = 100) String section, String teamFormation, Integer teamMinSize, Integer teamMaxSize,
        Boolean participantsMayChangeTeams) {}

    public record InstructorsRequest(@NotNull List<UUID> coordinatorIds) {}

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
            body.teamMaxSize(), body.participantsMayChangeTeams(), body.coordinatorIds()));
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

    /** Changing who teaches a class is an administrative act: peers must not be able to remove each other. */
    @PutMapping("/{id}/coordinators")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setCoordinators(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id,
                               @Valid @RequestBody InstructorsRequest body) {
        coordinators.setCoordinators(user, id, body.coordinatorIds());
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
