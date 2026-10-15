package com.blissfuljuan.dieapi.account.view;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Admin-facing view of an account. Never includes credential material. */
public record UserView(
    UUID id,
    String role,
    String status,
    String studentNumber,
    String lastName,
    String firstName,
    String middleName,
    String email,
    boolean emailVerified,
    OffsetDateTime createdAt,
    long version) {
}
