package com.blissfuljuan.dieapi.auth.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A PENDING_ACTIVATION account together with its live temporary credential (hash only). */
public record PendingActivation(UUID userId, String firstName, String lastName, String email, boolean emailVerified,
                                UUID credentialId, String secretHash, OffsetDateTime expiresAt) {}
