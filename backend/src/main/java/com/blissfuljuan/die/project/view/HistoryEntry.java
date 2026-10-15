package com.blissfuljuan.die.project.view;

import java.time.OffsetDateTime;
import java.util.UUID;

public record HistoryEntry(UUID userId, String firstName, String lastName, String role, OffsetDateTime from,
                           OffsetDateTime until, UUID changedBy) {}
