package com.blissfuljuan.die.roster.model;

import java.util.UUID;

/** An account that already owns a Student ID mentioned in a roster. Internal: never sent to clients. */
public record ExistingUser(UUID id, String role, String status, String lastName, String firstName, UUID createdBy) {}
