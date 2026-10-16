package com.blissfuljuan.dieapi.project.service.creation;

import com.blissfuljuan.dieapi.access.model.ProjectRole;
import com.blissfuljuan.dieapi.account.model.UserRole;
import com.blissfuljuan.dieapi.project.model.ProjectCreation;
import com.blissfuljuan.dieapi.project.model.ProjectKind;

import java.util.UUID;

/** One way of creating a project (who creates it x which kind). Runs with the class row already locked. */
public interface ProjectCreationStrategy {

    boolean supports(UserRole actor, ProjectKind kind);

    /** Checks the case-specific rules, inserts the project and its first members; returns the project id. */
    UUID create(ProjectCreation creation);

    /** The creator's role on the new project, used to render the response. */
    ProjectRole creatorRole();
}
