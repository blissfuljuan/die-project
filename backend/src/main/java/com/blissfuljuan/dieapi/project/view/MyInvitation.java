package com.blissfuljuan.dieapi.project.view;

import java.time.OffsetDateTime;
import java.util.UUID;

public record MyInvitation(UUID id, UUID projectId, UUID classId, String subjectCode, String projectTitle,
                           String teamName, String invitedBy, OffsetDateTime expiresAt) {}
