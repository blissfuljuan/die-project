package com.blissfuljuan.dieapi.account.model;

import java.util.UUID;

/** Account row loaded with FOR UPDATE for guarded changes. */
public record LockedAccount(UUID id, String role, String status, boolean hasPassword, boolean emailVerified,
                            long version, String studentNumber, String lastName, String firstName,
                            String middleName, String email) {}
