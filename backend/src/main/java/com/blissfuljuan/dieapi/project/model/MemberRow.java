package com.blissfuljuan.dieapi.project.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/** joinedAt = first time the user joined this project (stable across role changes). */
public record MemberRow(UUID userId, String firstName, String lastName, String studentNumber, TeamRole role,
                        OffsetDateTime joinedAt) {}
