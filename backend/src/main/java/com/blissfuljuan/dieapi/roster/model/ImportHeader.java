package com.blissfuljuan.dieapi.roster.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ImportHeader(UUID id, UUID classId, UUID uploadedBy, String fileName, String status,
                           OffsetDateTime createdAt, OffsetDateTime committedAt, String summaryJson) {}
