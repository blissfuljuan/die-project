package com.blissfuljuan.dieapi.academic.view;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** One assigned coordinator of a workspace with the capabilities they effectively hold (the Owner: all of them). */
public record CoordinatorEntry(UUID userId, String firstName, String lastName, boolean owner,
                               List<String> capabilities, OffsetDateTime assignedAt) {}
