package com.blissfuljuan.die.project.view;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ProjectView(UUID id, UUID classId, String kind, String title, String description, String teamName,
                          String status, long version, OffsetDateTime createdAt, String myRole,
                          List<MemberView> members, TeamPolicy teamPolicy, boolean meetsMinSize,
                          List<InvitationView> pendingInvitations) {}
