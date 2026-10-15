package com.blissfuljuan.die.roster.view;

import java.util.List;
import java.util.UUID;

public record CommitResult(UUID importId, boolean alreadyCommitted, int created, int enrolledExisting,
                           int skippedDuplicates, int skippedByResolution, int invalid,
                           List<IssuedCredential> credentials) {}
