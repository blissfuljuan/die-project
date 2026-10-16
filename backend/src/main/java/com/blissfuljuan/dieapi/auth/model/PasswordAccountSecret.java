package com.blissfuljuan.dieapi.auth.model;

/** Email, first name and current password hash of a user, locked for a password change. */
public record PasswordAccountSecret(String email, String firstName, String passwordHash) {}
