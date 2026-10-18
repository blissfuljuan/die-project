package com.blissfuljuan.dieapi.project.service.adapter;

import com.blissfuljuan.dieapi.academic.service.port.ClassTeamStats;
import com.blissfuljuan.dieapi.academic.service.port.StudentRemovalHandler;
import com.blissfuljuan.dieapi.project.repository.ProjectRepository;
import com.blissfuljuan.dieapi.project.service.TeamMembership;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Adapter: the project module's implementation of the ports that the academic module defines. Both run in the
 * caller's transaction (no transaction of their own), so lock order and rollback behaviour are those of the
 * academic service that calls them.
 */
@Component
public class ProjectClassIntegration implements StudentRemovalHandler, ClassTeamStats {

    private final TeamMembership teamMembership;
    private final ProjectRepository projects;

    public ProjectClassIntegration(TeamMembership teamMembership, ProjectRepository projects) {
        this.teamMembership = teamMembership;
        this.projects = projects;
    }

    @Override
    public void onStudentRemovedFromClass(UUID workspaceId, UUID participantId, UUID actor) {
        teamMembership.onStudentRemovedFromClass(workspaceId, participantId, actor);
    }

    @Override
    public int largestActiveTeamSize(UUID workspaceId) {
        return projects.maxActiveTeamSize(workspaceId);
    }
}
