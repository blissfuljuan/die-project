package com.blissfuljuan.dieapi.academic.view;

import java.time.OffsetDateTime;

/** A freshly issued temporary credential. Shown once; only its hash is stored. */
public record TemporaryCredentialView(String secret, OffsetDateTime expiresAt) {}
