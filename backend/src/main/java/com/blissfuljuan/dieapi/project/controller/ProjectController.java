package com.blissfuljuan.dieapi.project.controller;

import com.blissfuljuan.dieapi.auth.model.AppUserDetails;
import com.blissfuljuan.dieapi.project.service.ProjectService;
import com.blissfuljuan.dieapi.project.view.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/** Projects, teams and invitations. Authorization is decided by AccessPolicy inside ProjectService. */
@RestController
@RequestMapping("/api/v1")
public class ProjectController {

    public record CreateProjectRequest(@Size(max = 20) String kind, @Size(max = 200) String title,
                                       @Size(max = 2000) String description, @Size(max = 100) String teamName,
                                       UUID studentId, List<UUID> memberIds, UUID representativeId,
                                       Boolean allowSizeException) {}

    public record UpdateProjectRequest(@NotNull Long version, @Size(max = 200) String title,
                                       @Size(max = 2000) String description, @Size(max = 100) String teamName) {}

    public record SetMembersRequest(@NotNull Long version, @NotNull List<UUID> memberIds, UUID representativeId,
                                    Boolean allowSizeException) {}

    public record RepresentativeRequest(@NotNull UUID studentId) {}

    public record InviteRequest(@NotNull UUID studentId) {}

    private final ProjectService projects;

    public ProjectController(ProjectService projects) {
        this.projects = projects;
    }

    @GetMapping("/classes/{classId}/projects")
    public List<ProjectView> list(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID classId) {
        return projects.list(user, classId);
    }

    @PostMapping("/classes/{classId}/projects")
    public ResponseEntity<ProjectView> create(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID classId,
                                              @Valid @RequestBody CreateProjectRequest body) {
        ProjectView created = projects.create(user, classId, new ProjectService.CreateProject(body.kind(), body.title(),
            body.description(), body.teamName(), body.studentId(), body.memberIds(), body.representativeId(),
            body.allowSizeException()));
        return ResponseEntity.created(URI.create("/api/v1/projects/" + created.id())).body(created);
    }

    @GetMapping("/classes/{classId}/eligible-classmates")
    public List<Classmate> eligibleClassmates(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID classId) {
        return projects.eligibleClassmates(user, classId);
    }

    @GetMapping("/projects/{id}")
    public ProjectView get(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id) {
        return projects.get(user, id);
    }

    @PatchMapping("/projects/{id}")
    public ProjectView update(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id,
                              @Valid @RequestBody UpdateProjectRequest body) {
        return projects.update(user, id, new ProjectService.UpdateProject(body.version(), body.title(),
            body.description(), body.teamName()));
    }

    @PostMapping("/projects/{id}/archive")
    public ProjectView archive(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id) {
        return projects.archive(user, id);
    }

    @PutMapping("/projects/{id}/members")
    public ProjectView setMembers(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id,
                                  @Valid @RequestBody SetMembersRequest body) {
        return projects.setMembers(user, id, new ProjectService.SetMembers(body.version(), body.memberIds(),
            body.representativeId(), body.allowSizeException()));
    }

    @PutMapping("/projects/{id}/representative")
    public ProjectView setRepresentative(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id,
                                         @Valid @RequestBody RepresentativeRequest body) {
        return projects.setRepresentative(user, id, body.studentId());
    }

    @GetMapping("/projects/{id}/membership-history")
    public List<HistoryEntry> history(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id) {
        return projects.history(user, id);
    }

    @PostMapping("/projects/{id}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id) {
        projects.leave(user, id);
    }

    // ---- invitations ----

    @PostMapping("/projects/{id}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public InvitationView invite(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id,
                                 @Valid @RequestBody InviteRequest body) {
        return projects.invite(user, id, body.studentId());
    }

    @DeleteMapping("/projects/{id}/invitations/{invitationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelInvitation(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id,
                                 @PathVariable UUID invitationId) {
        projects.cancelInvitation(user, id, invitationId);
    }

    @GetMapping("/invitations")
    public List<MyInvitation> myInvitations(@AuthenticationPrincipal AppUserDetails user) {
        return projects.myInvitations(user);
    }

    @PostMapping("/invitations/{id}/accept")
    public ProjectView accept(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id) {
        return projects.accept(user, id);
    }

    @PostMapping("/invitations/{id}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decline(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id) {
        projects.decline(user, id);
    }
}
