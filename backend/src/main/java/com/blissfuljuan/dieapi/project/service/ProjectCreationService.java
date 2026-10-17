package com.blissfuljuan.dieapi.project.service;

import com.blissfuljuan.dieapi.access.service.AccessPolicy;
import com.blissfuljuan.dieapi.account.model.UserRole;
import com.blissfuljuan.dieapi.audit.model.AuditAction;
import com.blissfuljuan.dieapi.audit.model.AuditEvent;
import com.blissfuljuan.dieapi.audit.model.AuditResource;
import org.springframework.context.ApplicationEventPublisher;
import com.blissfuljuan.dieapi.auth.model.AppUserDetails;
import com.blissfuljuan.dieapi.common.exception.ApiException;
import com.blissfuljuan.dieapi.common.exception.ErrorCode;
import com.blissfuljuan.dieapi.project.model.CreateProject;
import com.blissfuljuan.dieapi.project.model.ProjectCreation;
import com.blissfuljuan.dieapi.project.model.ProjectKind;
import com.blissfuljuan.dieapi.project.service.creation.ProjectCreationFactory;
import com.blissfuljuan.dieapi.project.service.creation.ProjectCreationStrategy;
import com.blissfuljuan.dieapi.project.view.ProjectView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static com.blissfuljuan.dieapi.project.service.ProjectValidation.text;

/**
 * Creates projects. Validates the shared fields, then delegates the case-specific rules to the
 * {@link ProjectCreationStrategy} chosen for the actor and kind. The class row is locked first.
 */
@Service
public class ProjectCreationService {

    private final AccessPolicy policy;
    private final ProjectMutation mutation;
    private final ProjectCreationFactory strategies;
    private final ProjectViews views;
    private final ApplicationEventPublisher events;

    public ProjectCreationService(AccessPolicy policy, ProjectMutation mutation, ProjectCreationFactory strategies,
                                  ProjectViews views, ApplicationEventPublisher events) {
        this.policy = policy;
        this.mutation = mutation;
        this.strategies = strategies;
        this.views = views;
        this.events = events;
    }

    @Transactional
    public ProjectView create(AppUserDetails user, UUID classId, CreateProject req) {
        if (user.role() == UserRole.STUDENT) {
            policy.requireClassView(user, classId);
        } else {
            policy.requireClassManage(user, classId);
        }
        var cls = mutation.lockWritableClass(classId);

        ProjectKind kind = ProjectKind.parse(req.kind());
        if (kind == null) throw ApiException.badRequest(ErrorCode.INVALID_PROJECT_KIND, "kind must be INDIVIDUAL or TEAM");
        String title = text(req.title(), "title", 200, true);
        String description = text(req.description(), "description", 2000, false);
        String teamName = text(req.teamName(), "teamName", 100, false);
        if (kind == ProjectKind.TEAM && teamName == null) teamName = title;
        if (kind == ProjectKind.INDIVIDUAL) teamName = null;

        ProjectCreationStrategy strategy = strategies.forCase(user.role(), kind);
        UUID projectId = strategy.create(new ProjectCreation(user, classId, cls, kind, title, description, teamName, req));
        events.publishEvent(AuditEvent.of(user.getId(), AuditAction.PROJECT_CREATED, AuditResource.PROJECT, projectId.toString(),
            Map.of("classId", classId.toString(), "kind", kind.name())));
        return views.viewOf(projectId, strategy.creatorRole());
    }
}
