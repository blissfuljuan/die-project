package com.blissfuljuan.dieapi.project.controller;

import com.blissfuljuan.dieapi.auth.model.AppUserDetails;
import com.blissfuljuan.dieapi.project.model.CreateProject;
import com.blissfuljuan.dieapi.project.model.SetMembers;
import com.blissfuljuan.dieapi.project.model.UpdateProject;
import com.blissfuljuan.dieapi.project.service.InvitationService;
import com.blissfuljuan.dieapi.project.service.ProjectCreationService;
import com.blissfuljuan.dieapi.project.service.ProjectLifecycleService;
import com.blissfuljuan.dieapi.project.service.ProjectMembershipService;
import com.blissfuljuan.dieapi.project.service.ProjectQueryService;
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

/** Projects, teams and invitations. Authorization is decided by AccessPolicy inside the project services. */
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

    private final ProjectQueryService queries;
    private final ProjectCreationService creation;
    private final ProjectLifecycleService lifecycle;
    private final ProjectMembershipService membership;
    private final InvitationService invitations;

    public ProjectController(ProjectQueryService queries, ProjectCreationService creation,
                             ProjectLifecycleService lifecycle, ProjectMembershipService membership,
                             InvitationService invitations) {
        this.queries = queries;
        this.creation = creation;
        this.lifecycle = lifecycle;
        this.membership = membership;
        this.invitations = invitations;
    }

    @GetMapping("/classes/{classId}/projects")
    public List<ProjectView> list(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID classId) {
        return queries.list(user, classId);
    }

    @PostMapping("/classes/{classId}/projects")
    public ResponseEntity<ProjectView> create(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID classId,
                                              @Valid @RequestBody CreateProjectRequest body) {
        ProjectView created = creation.create(user, classId, new CreateProject(body.kind(), body.title(),
            body.description(), body.teamName(), body.studentId(), body.memberIds(), body.representativeId(),
            body.allowSizeException()));
        return ResponseEntity.created(URI.create("/api/v1/projects/" + created.id())).body(created);
    }

    @GetMapping("/classes/{classId}/eligible-classmates")
    public List<Classmate> eligibleClassmates(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID classId) {
        return queries.eligibleClassmates(user, classId);
    }

    @GetMapping("/projects/{id}")
    public ProjectView get(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id) {
        return queries.get(user, id);
    }

    @PatchMapping("/projects/{id}")
    public ProjectView update(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id,
                              @Valid @RequestBody UpdateProjectRequest body) {
        return lifecycle.update(user, id, new UpdateProject(body.version(), body.title(),
            body.description(), body.teamName()));
    }

    @PostMapping("/projects/{id}/archive")
    public ProjectView archive(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id) {
        return lifecycle.archive(user, id);
    }

    @PutMapping("/projects/{id}/members")
    public ProjectView setMembers(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id,
                                  @Valid @RequestBody SetMembersRequest body) {
        return membership.setMembers(user, id, new SetMembers(body.version(), body.memberIds(),
            body.representativeId(), body.allowSizeException()));
    }

    @PutMapping("/projects/{id}/representative")
    public ProjectView setRepresentative(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id,
                                         @Valid @RequestBody RepresentativeRequest body) {
        return membership.setRepresentative(user, id, body.studentId());
    }

    @GetMapping("/projects/{id}/membership-history")
    public List<HistoryEntry> history(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id) {
        return queries.history(user, id);
    }

    @PostMapping("/projects/{id}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id) {
        membership.leave(user, id);
    }

    // ---- invitations ----

    @PostMapping("/projects/{id}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public InvitationView invite(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id,
                                 @Valid @RequestBody InviteRequest body) {
        return invitations.invite(user, id, body.studentId());
    }

    @DeleteMapping("/projects/{id}/invitations/{invitationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelInvitation(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id,
                                 @PathVariable UUID invitationId) {
        invitations.cancelInvitation(user, id, invitationId);
    }

    @GetMapping("/invitations")
    public List<MyInvitation> myInvitations(@AuthenticationPrincipal AppUserDetails user) {
        return queries.myInvitations(user);
    }

    @PostMapping("/invitations/{id}/accept")
    public ProjectView accept(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id) {
        return invitations.accept(user, id);
    }

    @PostMapping("/invitations/{id}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decline(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id) {
        invitations.decline(user, id);
    }
}
