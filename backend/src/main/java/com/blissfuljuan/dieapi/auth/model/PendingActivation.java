package com.blissfuljuan.dieapi.auth.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A PENDING_ACTIVATION account together with its live temporary credential (hash only). {@code lockedUntil} is null when not locked. */
public record PendingActivation(UUID userId, String firstName, String lastName, String email, boolean emailVerified,
                                UUID credentialId, String secretHash, OffsetDateTime expiresAt, OffsetDateTime lockedUntil) {

    public boolean lockedAt(OffsetDateTime now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }
}
