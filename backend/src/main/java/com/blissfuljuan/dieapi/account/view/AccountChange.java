package com.blissfuljuan.dieapi.account.view;

import java.time.OffsetDateTime;

/** Result of operations that may issue a one-time temporary credential (returned once, never stored in plaintext). */
public record AccountChange(UserView user, String temporaryCredential, OffsetDateTime temporaryCredentialExpiresAt) {
    /** Never print secrets: records are logged by Spring at DEBUG ("Read ... to [Request[...]]"). */
    @Override
    public String toString() {
        return "AccountChange[redacted]";
    }
}
