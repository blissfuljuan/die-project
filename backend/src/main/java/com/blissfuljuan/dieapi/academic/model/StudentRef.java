package com.blissfuljuan.dieapi.academic.model;

import com.blissfuljuan.dieapi.account.model.AccountStatus;
import com.blissfuljuan.dieapi.account.model.UserRole;

import java.util.UUID;

/** The few account facts enrollment decisions need. */
public record StudentRef(UUID id, UserRole role, AccountStatus status, String lastName, UUID createdBy) {}
