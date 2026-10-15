package com.blissfuljuan.die.academic.controller;

import com.blissfuljuan.die.academic.service.ClassService;
import com.blissfuljuan.die.academic.view.ClassPage;
import com.blissfuljuan.die.academic.view.ClassView;
import com.blissfuljuan.die.academic.view.EnrollmentView;
import com.blissfuljuan.die.academic.view.TemporaryCredentialView;
import com.blissfuljuan.die.auth.model.AppUserDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Classes and enrollments. Role gates here are coarse; the fine-grained decision (admin, assigned
 * instructor, enrolled student) is always made by AccessPolicy inside ClassService.
 */
@RestController
@RequestMapping("/api/v1/classes")
public class ClassController {

    public record CreateClassRequest(
        @Size(max = 100) String academicYear, @Size(max = 100) String semester,
        @Size(max = 100) String subjectCode, @Size(max = 100) String subjectName,
        @Size(max = 100) String section, String teamFormation, Integer teamMinSize, Integer teamMaxSize,
        Boolean studentsMayChangeTeams, List<UUID> instructorIds) {}

    public record UpdateClassRequest(
        @NotNull Long version,
        @Size(max = 100) String academicYear, @Size(max = 100) String semester,
        @Size(max = 100) String subjectCode, @Size(max = 100) String subjectName,
        @Size(max = 100) String section, String teamFormation, Integer teamMinSize, Integer teamMaxSize,
        Boolean studentsMayChangeTeams) {}

    public record InstructorsRequest(@NotNull List<UUID> instructorIds) {}

    /** lastName is required for instructors enrolling by studentNumber (admins may omit it). */
    public record EnrollRequest(UUID studentId, @Size(max = 64) String studentNumber, @Size(max = 100) String lastName,
                                @Size(max = 100) String program, @Size(max = 100) String section) {}

    private final ClassService classes;

    public ClassController(ClassService classes) {
        this.classes = classes;
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
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR')")
    public ResponseEntity<ClassView> create(@AuthenticationPrincipal AppUserDetails user,
                                            @Valid @RequestBody CreateClassRequest body) {
        ClassView created = classes.create(user, new ClassService.CreateClass(body.academicYear(), body.semester(),
            body.subjectCode(), body.subjectName(), body.section(), body.teamFormation(), body.teamMinSize(),
            body.teamMaxSize(), body.studentsMayChangeTeams(), body.instructorIds()));
        return ResponseEntity.created(URI.create("/api/v1/classes/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    public ClassView get(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id) {
        return classes.get(user, id);
    }

    @PatchMapping("/{id}")
    public ClassView update(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id,
                            @Valid @RequestBody UpdateClassRequest body) {
        return classes.update(user, id, new ClassService.UpdateClass(body.version(), body.academicYear(),
            body.semester(), body.subjectCode(), body.subjectName(), body.section(), body.teamFormation(),
            body.teamMinSize(), body.teamMaxSize(), body.studentsMayChangeTeams()));
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
    @PutMapping("/{id}/instructors")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setInstructors(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id,
                               @Valid @RequestBody InstructorsRequest body) {
        classes.setInstructors(user, id, body.instructorIds());
    }

    @GetMapping("/{id}/enrollments")
    public List<EnrollmentView> roster(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id,
                                       @RequestParam(defaultValue = "false") boolean includeRemoved) {
        return classes.roster(user, id, includeRemoved);
    }

    @PostMapping("/{id}/enrollments")
    @ResponseStatus(HttpStatus.CREATED)
    public void enroll(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id,
                       @Valid @RequestBody EnrollRequest body) {
        classes.enroll(user, id, new ClassService.Enroll(body.studentId(), body.studentNumber(), body.lastName(),
            body.program(), body.section()));
    }

    /** Staff of the class only; for students who have not activated their account yet. Returned once. */
    @PostMapping("/{id}/enrollments/{studentId}/temporary-credential")
    public ResponseEntity<TemporaryCredentialView> reissueTemporaryCredential(
            @AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id, @PathVariable UUID studentId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(classes.reissueTemporaryCredential(user, id, studentId));
    }

    @DeleteMapping("/{id}/enrollments/{studentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unenroll(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id,
                         @PathVariable UUID studentId) {
        classes.unenroll(user, id, studentId);
    }
}
