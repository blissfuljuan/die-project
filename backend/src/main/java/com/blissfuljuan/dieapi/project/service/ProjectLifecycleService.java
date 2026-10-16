package com.blissfuljuan.dieapi.project.service;

import com.blissfuljuan.dieapi.access.service.AccessPolicy;
import com.blissfuljuan.dieapi.audit.model.AuditAction;
import com.blissfuljuan.dieapi.audit.model.AuditEvent;
import com.blissfuljuan.dieapi.audit.model.AuditResource;
import org.springframework.context.ApplicationEventPublisher;
import com.blissfuljuan.dieapi.auth.model.AppUserDetails;
import com.blissfuljuan.dieapi.common.exception.ApiException;
import com.blissfuljuan.dieapi.project.model.ProjectKind;
import com.blissfuljuan.dieapi.project.model.ProjectStatus;
import com.blissfuljuan.dieapi.project.model.UpdateProject;
import com.blissfuljuan.dieapi.project.repository.ProjectRepository;
import com.blissfuljuan.dieapi.project.service.ProjectMutation.Requires;
import com.blissfuljuan.dieapi.project.view.ProjectView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static com.blissfuljuan.dieapi.project.service.ProjectValidation.text;

/** Changing a project's details and archiving it. */
@Service
public class ProjectLifecycleService {

    private final ProjectRepository repo;
    private final AccessPolicy policy;
    private final ProjectMutation mutation;
    private final ProjectViews views;
    private final ApplicationEventPublisher events;

    public ProjectLifecycleService(ProjectRepository repo, AccessPolicy policy, ProjectMutation mutation,
                                   ProjectViews views, ApplicationEventPublisher events) {
        this.repo = repo;
        this.policy = policy;
        this.mutation = mutation;
        this.views = views;
        this.events = events;
    }

    @Transactional
    public ProjectView update(AppUserDetails user, UUID projectId, UpdateProject req) {
        ProjectValidation.requireVersion(req.version());
        return mutation.run(projectId, Requires.ACTIVE_PROJECT, () -> policy.requireProjectEdit(user, projectId),
            req.version(), (locked, role) -> {
                var current = locked.project();
                String title = req.title() == null ? current.title() : text(req.title(), "title", 200, true);
                String description = req.description() == null ? current.description()
                    : text(req.description(), "description", 2000, false);
                String teamName = current.teamName();
                if (req.teamName() != null) {
                    if (current.kind() == ProjectKind.INDIVIDUAL) {
                        throw ApiException.badRequest("INDIVIDUAL_PROJECT_NO_TEAM_NAME", "An individual project has no team name");
                    }
                    teamName = text(req.teamName(), "teamName", 100, true);
                }
                repo.updateDetails(projectId, title, description, teamName);
                events.publishEvent(AuditEvent.of(user.getId(), AuditAction.PROJECT_UPDATED, AuditResource.PROJECT, projectId.toString(), Map.of()));
                return views.viewOf(projectId, role);
            });
    }

    @Transactional
    public ProjectView archive(AppUserDetails user, UUID projectId) {
        return mutation.run(projectId, Requires.ANY_STATE, () -> policy.requireProjectStaff(user, projectId),
            null, (locked, role) -> {
                if (locked.project().status() != ProjectStatus.ARCHIVED) {
                    repo.setStatus(projectId, ProjectStatus.ARCHIVED);
                    repo.cancelAllPendingForProject(projectId);
                    events.publishEvent(AuditEvent.of(user.getId(), AuditAction.PROJECT_ARCHIVED, AuditResource.PROJECT, projectId.toString(), Map.of()));
                }
                return views.viewOf(projectId, role);
            });
    }
}
