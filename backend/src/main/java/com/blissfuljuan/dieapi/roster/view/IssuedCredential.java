package com.blissfuljuan.dieapi.roster.view;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One-time temporary credential of a newly created account; only ever returned by the commit response. */
public record IssuedCredential(int rowNumber, UUID participantId, String studentNumber, String lastName,
                               String firstName, String temporaryCredential, OffsetDateTime expiresAt) {
    /** Never print secrets: records are logged by Spring at DEBUG ("Read ... to [Request[...]]"). */
    @Override
    public String toString() {
        return "IssuedCredential[redacted]";
    }
}
