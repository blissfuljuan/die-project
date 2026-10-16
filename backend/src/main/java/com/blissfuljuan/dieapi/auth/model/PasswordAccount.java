package com.blissfuljuan.dieapi.auth.model;

import java.util.UUID;

/** The part of an account that password recovery needs. */
public record PasswordAccount(UUID id, String email, String firstName) {}
