package com.blissfuljuan.dieapi.auth.model;

import java.util.UUID;

/** A live (unconsumed, unexpired) email verification token joined with its user, as locked for verification. */
public record VerificationLink(UUID tokenId, UUID userId, String tokenEmail, String userEmail, String status,
                               boolean hasPassword) {}
