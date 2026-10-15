package io.die.evaluator.roster;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** API and persistence shapes of the roster import feature. */
public final class RosterModels {

    private RosterModels() {}

    public static final String CREATE = "CREATE";
    public static final String MATCH = "MATCH";
    public static final String SKIP_DUPLICATE = "SKIP_DUPLICATE";
    public static final String CONFLICT = "CONFLICT";
    public static final String INVALID = "INVALID";

    public static final String USE_EXISTING = "USE_EXISTING";
    public static final String SKIP = "SKIP";
    public static final String CREATE_NEW = "CREATE_NEW";

    /** Counts by row outcome; unresolvedConflicts must be 0 before commit. */
    public record Summary(int total, int create, int match, int skipDuplicate, int conflict, int invalid,
                          int unresolvedConflicts) {}

    public record ImportView(UUID id, UUID classId, String fileName, String status, OffsetDateTime createdAt,
                             OffsetDateTime committedAt, Summary summary) {}

    /** Deliberately has no account ids: the client works with row numbers only. */
    public record RowView(int rowNumber, String studentNumber, String lastName, String firstName,
                          String middleName, String email, String program, String section, String action,
                          String conflictKind, String resolution, String message,
                          List<String> allowedResolutions) {}

    public record ImportDetail(ImportView importInfo, List<RowView> rows, int page, int size, long totalRows) {}

    public record IssuedCredential(int rowNumber, UUID studentId, String studentNumber, String lastName,
                                   String firstName, String temporaryCredential, OffsetDateTime expiresAt) {}

    public record CommitResult(UUID importId, boolean alreadyCommitted, int created, int enrolledExisting,
                               int skippedDuplicates, int skippedByResolution, int invalid,
                               List<IssuedCredential> credentials) {}

    public record ResolutionChange(int rowNumber, String resolution) {}

    // ---- internal ----

    /** Persisted import row (also the analyzer's output). */
    public record StoredRow(int rowNumber, String studentNumber, String lastName, String firstName,
                            String middleName, String email, String program, String section, String action,
                            String conflictKind, String resolution, String message, UUID matchedUserId) {}

    public record ImportHeader(UUID id, UUID classId, UUID uploadedBy, String fileName, String status,
                               OffsetDateTime createdAt, OffsetDateTime committedAt, String summaryJson) {}

    public static List<String> allowedResolutions(String conflictKind) {
        if (conflictKind == null) return List.of();
        return switch (conflictKind) {
            case "ID_NAME_MISMATCH" -> List.of(USE_EXISTING, SKIP);   // only for students the caller already teaches
            default -> List.of(SKIP);
        };
    }
}
