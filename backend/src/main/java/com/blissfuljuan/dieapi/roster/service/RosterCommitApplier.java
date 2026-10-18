package com.blissfuljuan.dieapi.roster.service;

import com.blissfuljuan.dieapi.academic.service.EnrollmentRules;
import com.blissfuljuan.dieapi.account.service.TempCredentialService;
import com.blissfuljuan.dieapi.roster.model.CommitEffect;
import com.blissfuljuan.dieapi.roster.model.StoredRow;
import com.blissfuljuan.dieapi.roster.model.rule.ConflictResolvers;
import com.blissfuljuan.dieapi.roster.repository.RosterImportRepository;
import com.blissfuljuan.dieapi.roster.view.CommitResult;
import com.blissfuljuan.dieapi.roster.view.IssuedCredential;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The writing step of a commit: creates the new accounts, enrolls matched students and applies resolved
 * conflicts. Must run inside the commit's write transaction, after the preview has been re-validated; it only
 * inserts the pre-computed credential hashes and never hashes anything itself.
 */
@Component
public class RosterCommitApplier {

    private final RosterImportRepository repo;
    private final EnrollmentRules enrollmentRules;
    private final TempCredentialService tempCredentials;
    private final ConflictResolvers resolvers;

    public RosterCommitApplier(RosterImportRepository repo, EnrollmentRules enrollmentRules,
                               TempCredentialService tempCredentials, ConflictResolvers resolvers) {
        this.repo = repo;
        this.enrollmentRules = enrollmentRules;
        this.tempCredentials = tempCredentials;
        this.resolvers = resolvers;
    }

    public CommitResult apply(UUID importId, UUID workspaceId, List<StoredRow> stored, UUID committer,
                              List<TempCredentialService.Prepared> prepared) {
        int enrolledExisting = 0, skippedDuplicates = 0, skippedByResolution = 0, invalid = 0;
        List<StoredRow> createdRows = new ArrayList<>();
        List<UUID> createdIds = new ArrayList<>();
        for (StoredRow r : stored) {
            switch (r.action()) {
                case CREATE -> createdIds.add(createAccount(workspaceId, r, committer, createdRows));
                case MATCH -> enrolledExisting += enrollExisting(workspaceId, r) ? 1 : 0;
                case SKIP_DUPLICATE -> skippedDuplicates++;
                case INVALID -> invalid++;
                case CONFLICT -> {
                    CommitEffect effect = resolvers.forKind(r.conflictKind()).effectOf(r.resolution());
                    switch (effect) {
                        case ENROLL_EXISTING -> enrolledExisting += enrollExisting(workspaceId, r) ? 1 : 0;
                        case SKIP -> skippedByResolution++;
                    }
                }
            }
        }

        List<TempCredentialService.Issued> issued = tempCredentials.storePrepared(createdIds, prepared, committer);
        List<IssuedCredential> credentials = new ArrayList<>(createdIds.size());
        for (int i = 0; i < createdIds.size(); i++) {
            StoredRow r = createdRows.get(i);
            credentials.add(new IssuedCredential(r.rowNumber(), createdIds.get(i), r.studentNumber(), r.lastName(),
                r.firstName(), issued.get(i).secret(), issued.get(i).expiresAt()));
        }
        return new CommitResult(importId, false, createdIds.size(), enrolledExisting, skippedDuplicates,
            skippedByResolution, invalid, credentials);
    }

    private UUID createAccount(UUID workspaceId, StoredRow r, UUID createdBy, List<StoredRow> createdRows) {
        UUID id = repo.insertPendingStudent(r.studentNumber(), r.lastName(), r.firstName(), r.middleName(), createdBy);
        enrollmentRules.enroll(workspaceId, id, r.program(), r.section());
        createdRows.add(r);
        return id;
    }

    /** @return true if the student was newly enrolled or reinstated. */
    private boolean enrollExisting(UUID workspaceId, StoredRow r) {
        return enrollmentRules.enroll(workspaceId, r.matchedUserId(), r.program(), r.section())
            != EnrollmentRules.Outcome.ALREADY_ENROLLED;
    }
}
