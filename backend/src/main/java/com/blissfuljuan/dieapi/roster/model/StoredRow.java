package com.blissfuljuan.dieapi.roster.model;

import java.util.UUID;

/** Persisted import row (also the analyzer's output). conflictKind and resolution are null unless it is a conflict. */
public record StoredRow(int rowNumber, String studentNumber, String lastName, String firstName,
                        String middleName, String email, String program, String section, RowOutcome action,
                        ConflictKind conflictKind, ResolutionAction resolution, String message, UUID matchedUserId) {}
