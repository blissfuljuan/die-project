package com.blissfuljuan.dieapi.account.view;

import java.time.OffsetDateTime;

/** Result of operations that may issue a one-time temporary credential (returned once, never stored in plaintext). */
public record AccountChange(UserView user, String temporaryCredential, OffsetDateTime temporaryCredentialExpiresAt) {}
