package com.blissfuljuan.dieapi.project.model;

import com.blissfuljuan.dieapi.academic.model.LockedClass;
import com.blissfuljuan.dieapi.auth.model.AppUserDetails;

import java.util.UUID;

/** Validated input handed to a creation strategy; the class row is already locked. */
public record ProjectCreation(AppUserDetails actor, UUID workspaceId, LockedClass cls, ProjectKind kind, String title,
                              String description, String teamName, CreateProject request) {}
