package com.blissfuljuan.die.roster.model;

import java.util.UUID;

/** Persisted import row (also the analyzer's output). */
public record StoredRow(int rowNumber, String studentNumber, String lastName, String firstName,
                        String middleName, String email, String program, String section, String action,
                        String conflictKind, String resolution, String message, UUID matchedUserId) {}
