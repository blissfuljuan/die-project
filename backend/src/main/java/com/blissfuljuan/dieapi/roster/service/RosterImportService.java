package com.blissfuljuan.dieapi.roster.service;

import com.blissfuljuan.dieapi.academic.repository.ClassRepository;
import com.blissfuljuan.dieapi.access.service.AccessPolicy;
import com.blissfuljuan.dieapi.account.service.CredentialHasher;
import com.blissfuljuan.dieapi.account.service.TempCredentialService;
import com.blissfuljuan.dieapi.audit.model.AuditAction;
import com.blissfuljuan.dieapi.audit.model.AuditEvent;
import com.blissfuljuan.dieapi.audit.model.AuditResource;
import org.springframework.context.ApplicationEventPublisher;
import com.blissfuljuan.dieapi.academic.model.ClassStatus;
import com.blissfuljuan.dieapi.auth.model.AppUserDetails;
import com.blissfuljuan.dieapi.common.exception.ApiException;
import com.blissfuljuan.dieapi.common.util.Tokens;
import com.blissfuljuan.dieapi.roster.model.*;
import com.blissfuljuan.dieapi.roster.repository.RosterImportRepository;
import com.blissfuljuan.dieapi.roster.model.rule.ConflictResolvers;
import com.blissfuljuan.dieapi.roster.view.CommitResult;
import com.blissfuljuan.dieapi.roster.view.ImportView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/**
 * Roster import: upload -> preview -> resolve conflicts -> commit.
 *
 * Authorization is always the class's: only admins and instructors assigned to the class may touch
 * an import; anyone else gets the same 404 as for an import that does not exist.
 * Commit is one transaction. It re-analyzes the stored rows against the current database and refuses
 * (409, nothing written) if any outcome changed since the preview. Temporary credentials are returned
 * exactly once, in the commit response, and are never persisted in plaintext.
 */
@Service
public class RosterImportService {

    private final RosterImportRepository repo;
    private final RosterImportQueryService queries;
    private final RosterParser parser;
    private final RosterAnalyzer analyzer;
    private final RosterCommitApplier applier;
    private final ConflictResolvers resolvers;
    private final AccessPolicy policy;
    private final ClassRepository classes;
    private final ApplicationEventPublisher events;
    private final RosterCommitSupport support;
    private final CommitLimiter limiter;
    private final CredentialHasher hasher;
    private final PlatformTransactionManager txManager;

    public RosterImportService(RosterImportRepository repo, RosterImportQueryService queries, RosterParser parser,
                               RosterAnalyzer analyzer, RosterCommitApplier applier, ConflictResolvers resolvers,
                               AccessPolicy policy, ClassRepository classes, ApplicationEventPublisher events, RosterCommitSupport support,
                               CommitLimiter limiter, CredentialHasher hasher, PlatformTransactionManager txManager) {
        this.repo = repo;
        this.queries = queries;
        this.parser = parser;
        this.analyzer = analyzer;
        this.applier = applier;
        this.resolvers = resolvers;
        this.policy = policy;
        this.classes = classes;
        this.events = events;
        this.support = support;
        this.limiter = limiter;
        this.hasher = hasher;
        this.txManager = txManager;
    }

    // ---- upload / preview ----

    @Transactional
    public ImportView upload(AppUserDetails user, UUID classId, String rawFileName, byte[] bytes) {
        policy.requireClassManage(user, classId);

        // Parse (CPU/memory heavy, no database) before taking the class lock, so a slow or hostile file
        // cannot block enrollment and team changes for the whole class.
        String fileName = RosterParser.cleanFileName(rawFileName);
        List<NormalizedRow> parsed = parser.parse(fileName, bytes);
        assertWritable(classId);
        List<StoredRow> analyzed = analyzer.classify(classId, parsed, user);

        UUID id = repo.insertImport(classId, user.getId(), fileName, Tokens.sha256(bytes));
        analyzed.forEach(r -> repo.insertRow(id, r));
        events.publishEvent(AuditEvent.of(user.getId(), AuditAction.ROSTER_IMPORT_PREVIEWED, AuditResource.CLASS, classId.toString(),
            Map.of("importId", id.toString(), "rows", analyzed.size())));
        return queries.view(repo.find(id, false).orElseThrow());
    }

    // ---- conflict resolution ----

    @Transactional
    public ImportView resolve(AppUserDetails user, UUID importId, List<ResolutionChange> changes) {
        ImportHeader h = queries.header(user, importId, true);
        requirePreview(h);
        if (changes == null || changes.isEmpty()) throw bad("RESOLUTIONS_REQUIRED", "resolutions must not be empty");
        for (ResolutionChange c : changes) {
            StoredRow row = repo.row(importId, c.rowNumber()).orElseThrow(() -> bad("ROW_NOT_FOUND", "Row " + c.rowNumber() + " does not exist"));
            if (row.action() != RowOutcome.CONFLICT) {
                throw bad("ROW_NOT_A_CONFLICT", "Row " + c.rowNumber() + " is not a conflict");
            }
            ResolutionAction chosen = ResolutionAction.parse(c.resolution());
            List<ResolutionAction> allowed = resolvers.forKind(row.conflictKind()).allowed();
            if (chosen == null || !allowed.contains(chosen)) {
                throw bad("RESOLUTION_NOT_ALLOWED", "Row " + c.rowNumber() + " (" + row.conflictKind() + ") accepts only "
                    + allowed);
            }
            repo.setResolution(importId, c.rowNumber(), chosen);
        }
        events.publishEvent(AuditEvent.of(user.getId(), AuditAction.ROSTER_CONFLICTS_RESOLVED, AuditResource.CLASS, h.classId().toString(),
            Map.of("importId", importId.toString(), "count", changes.size())));
        return queries.view(h);
    }

    // ---- commit ----

    /** What the cheap, lock-free first phase of a commit learned. */
    private record Preflight(CommitResult alreadyCommitted, int credentialsNeeded) {}

    /**
     * Commit in three phases so the expensive part never holds database resources:
     *  1. a short read-only transaction: authorize, check the state, count the accounts to be created;
     *  2. (admission control, then) hash that many temporary credentials on the dedicated bounded pool,
     *     with NO transaction, connection or lock held;
     *  3. one write transaction that locks, re-validates against the current database and applies the import,
     *     storing the pre-computed hashes (pure inserts).
     * A commit refused by the limiter (429) or the hasher (503) has changed nothing.
     */
    public CommitResult commit(AppUserDetails user, UUID importId) {
        Preflight pre = new TransactionTemplate(txManager, readOnlyDefinition()).execute(s -> preflight(user, importId));
        if (pre.alreadyCommitted() != null) {
            return pre.alreadyCommitted();
        }
        try (CommitLimiter.Permit ignored = limiter.acquire(user.getId())) {
            List<TempCredentialService.Prepared> prepared = hasher.prepare(pre.credentialsNeeded());
            return new TransactionTemplate(txManager).execute(s -> commitLocked(user, importId, prepared));
        }
    }

    private static DefaultTransactionDefinition readOnlyDefinition() {
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setReadOnly(true);
        return def;
    }

    private Preflight preflight(AppUserDetails user, UUID importId) {
        ImportHeader h = queries.header(user, importId, false);
        if (h.status() == ImportStatus.COMMITTED) {
            return new Preflight(support.previouslyCommitted(h), 0);
        }
        requirePreview(h);
        return new Preflight(null, (int) repo.countRows(importId, RowOutcome.CREATE));
    }

    private CommitResult commitLocked(AppUserDetails user, UUID importId, List<TempCredentialService.Prepared> prepared) {
        ImportHeader h = queries.header(user, importId, true);
        if (h.status() == ImportStatus.COMMITTED) {
            return support.previouslyCommitted(h);           // another commit won the race while we were hashing
        }
        requirePreview(h);
        UUID classId = h.classId();
        assertWritable(classId);

        List<StoredRow> stored = repo.allRows(importId);
        int unresolved = repo.summary(importId).unresolvedConflicts();
        if (unresolved > 0) {
            throw ApiException.conflict("UNRESOLVED_CONFLICTS", unresolved + " conflict(s) still need a resolution");
        }
        support.assertUnchangedSincePreview(classId, stored, user);
        if (stored.stream().filter(r -> r.action() == RowOutcome.CREATE).count() != prepared.size()) {
            throw ApiException.conflict("IMPORT_CHANGED", "The import changed while it was being prepared; try again");
        }

        CommitResult result = applier.apply(importId, classId, stored, user.getId(), prepared);

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("created", result.created());
        counts.put("enrolledExisting", result.enrolledExisting());
        counts.put("skippedDuplicates", result.skippedDuplicates());
        counts.put("skippedByResolution", result.skippedByResolution());
        counts.put("invalid", result.invalid());
        repo.markCommitted(importId, user.getId(), support.json(counts));

        Map<String, Object> auditDetails = new LinkedHashMap<>(counts);
        auditDetails.put("importId", importId.toString());
        auditDetails.put("createdAccounts", result.credentials().stream().map(c -> c.studentId().toString()).toList());
        events.publishEvent(AuditEvent.of(user.getId(), AuditAction.ROSTER_IMPORT_COMMITTED, AuditResource.CLASS, classId.toString(), auditDetails));
        return result;
    }

    @Transactional
    public void cancel(AppUserDetails user, UUID importId) {
        ImportHeader h = queries.header(user, importId, true);
        if (h.status() == ImportStatus.COMMITTED) {
            throw ApiException.conflict("IMPORT_ALREADY_COMMITTED", "A committed import cannot be cancelled");
        }
        if (h.status() == ImportStatus.PREVIEW) {
            repo.setStatus(importId, ImportStatus.CANCELLED);
            events.publishEvent(AuditEvent.of(user.getId(), AuditAction.ROSTER_IMPORT_CANCELLED, AuditResource.CLASS, h.classId().toString(),
                Map.of("importId", importId.toString())));
        }
    }

    // ---- helpers ----

    private void assertWritable(UUID classId) {
        var cls = classes.lock(classId).orElseThrow(AccessPolicy::classNotFound);
        if (cls.status() == ClassStatus.ARCHIVED) {
            throw ApiException.conflict("CLASS_ARCHIVED", "The class is archived and read-only");
        }
    }

    private static void requirePreview(ImportHeader h) {
        if (h.status() != ImportStatus.PREVIEW) {
            throw ApiException.conflict("IMPORT_NOT_PENDING", "The import is " + h.status().name().toLowerCase());
        }
    }

    private static ApiException bad(String code, String reason) {
        return ApiException.badRequest(code, reason);
    }
}
