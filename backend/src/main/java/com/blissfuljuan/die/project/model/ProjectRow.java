package com.blissfuljuan.die.project.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ProjectRow(UUID id, UUID classId, String kind, String title, String description, String teamName,
                         String status, long version, OffsetDateTime createdAt) {}
