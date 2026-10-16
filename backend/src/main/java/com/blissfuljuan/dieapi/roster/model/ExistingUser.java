package com.blissfuljuan.dieapi.roster.model;

import com.blissfuljuan.dieapi.account.model.AccountStatus;
import com.blissfuljuan.dieapi.account.model.UserRole;

import java.util.UUID;

/** An account that already owns a Student ID mentioned in a roster. Internal: never sent to clients. */
public record ExistingUser(UUID id, UserRole role, AccountStatus status, String lastName, String firstName, UUID createdBy) {}
