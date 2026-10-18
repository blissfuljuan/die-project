package com.blissfuljuan.dieapi.project.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ProjectRow(UUID id, UUID workspaceId, ProjectKind kind, String title, String description, String teamName,
                         ProjectStatus status, long version, OffsetDateTime createdAt) {}
