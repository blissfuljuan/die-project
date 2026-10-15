package com.blissfuljuan.die.account.model;

/** Command: change an account; {@code version} is the caller's last-seen version (optimistic locking). */
public record UpdateUser(Long version, String role, String studentNumber, String lastName,
                         String firstName, String middleName, String email) {}
