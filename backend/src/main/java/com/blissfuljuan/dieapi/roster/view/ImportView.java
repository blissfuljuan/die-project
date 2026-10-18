package com.blissfuljuan.dieapi.roster.view;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ImportView(UUID id, UUID workspaceId, String fileName, String status, OffsetDateTime createdAt,
                         OffsetDateTime committedAt, Summary summary) {}
