package com.blissfuljuan.dieapi.roster.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ImportHeader(UUID id, UUID workspaceId, UUID uploadedBy, String fileName, ImportStatus status,
                           OffsetDateTime createdAt, OffsetDateTime committedAt, String summaryJson) {}
