package com.blissfuljuan.dieapi.academic.view;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Roster row. Staff-only: never returned to students. */
public record EnrollmentView(
    UUID participantId,
    String studentNumber,
    String lastName,
    String firstName,
    String middleName,
    String email,
    String accountStatus,
    String program,
    String section,
    String status,
    OffsetDateTime enrolledAt,
    OffsetDateTime removedAt) {
}
