package com.blissfuljuan.dieapi.project.view;

import java.time.OffsetDateTime;
import java.util.UUID;

public record InvitationView(UUID id, UUID participantId, String firstName, String lastName, String status,
                             OffsetDateTime expiresAt, OffsetDateTime createdAt) {}
