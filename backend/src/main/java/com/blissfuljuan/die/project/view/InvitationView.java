package com.blissfuljuan.die.project.view;

import java.time.OffsetDateTime;
import java.util.UUID;

public record InvitationView(UUID id, UUID studentId, String firstName, String lastName, String status,
                             OffsetDateTime expiresAt, OffsetDateTime createdAt) {}
