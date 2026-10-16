package com.blissfuljuan.dieapi.project.service.creation;

import com.blissfuljuan.dieapi.account.model.UserRole;
import com.blissfuljuan.dieapi.project.model.ProjectKind;
import org.springframework.stereotype.Component;

import java.util.List;

/** Picks the creation strategy for an actor and project kind from all registered strategies. */
@Component
public class ProjectCreationFactory {

    private final List<ProjectCreationStrategy> strategies;

    public ProjectCreationFactory(List<ProjectCreationStrategy> strategies) {
        this.strategies = strategies;
    }

    public ProjectCreationStrategy forCase(UserRole actor, ProjectKind kind) {
        return strategies.stream().filter(s -> s.supports(actor, kind)).findFirst()
            .orElseThrow(() -> new IllegalStateException("No project creation strategy for " + actor + " / " + kind));
    }
}
