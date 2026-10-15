package com.blissfuljuan.die.project.view;

import java.time.OffsetDateTime;
import java.util.UUID;

/** studentNumber is only filled for staff; students see classmates' names but not their numbers. */
public record MemberView(UUID userId, String firstName, String lastName, String studentNumber, String role,
                         OffsetDateTime joinedAt) {}
