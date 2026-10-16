package com.blissfuljuan.dieapi.auth.model;

import java.util.UUID;

/** A live password reset token joined with its (ACTIVE) user. */
public record ResetLink(UUID tokenId, UUID userId, String email, String firstName) {}
