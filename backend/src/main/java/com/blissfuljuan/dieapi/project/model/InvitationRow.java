package com.blissfuljuan.dieapi.project.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record InvitationRow(UUID id, UUID projectId, UUID invitedUserId, UUID invitedBy, InvitationStatus status,
                            OffsetDateTime expiresAt) {}
