package com.blissfuljuan.die.roster.service;

import static com.blissfuljuan.die.roster.model.RosterRules.CONFLICT;
import static com.blissfuljuan.die.roster.model.RosterRules.CREATE;
import static com.blissfuljuan.die.roster.model.RosterRules.INVALID;
import static com.blissfuljuan.die.roster.model.RosterRules.MATCH;
import static com.blissfuljuan.die.roster.model.RosterRules.SKIP;
import static com.blissfuljuan.die.roster.model.RosterRules.SKIP_DUPLICATE;
import static com.blissfuljuan.die.roster.model.RosterRules.USE_EXISTING;

import com.blissfuljuan.die.academic.repository.ClassRepository;
import com.blissfuljuan.die.academic.service.EnrollmentRules;
import com.blissfuljuan.die.access.service.AccessPolicy;
import com.blissfuljuan.die.account.service.CredentialHasher;
import com.blissfuljuan.die.account.service.TempCredentialService;
import com.blissfuljuan.die.audit.service.AuditService;
import com.blissfuljuan.die.auth.model.AppUserDetails;
import com.blissfuljuan.die.common.exception.ApiException;
import com.blissfuljuan.die.common.util.Tokens;
import com.blissfuljuan.die.roster.model.ImportHeader;
import com.blissfuljuan.die.roster.model.NormalizedRow;
import com.blissfuljuan.die.roster.model.ResolutionChange;
import com.blissfuljuan.die.roster.model.RosterRules;
import com.blissfuljuan.die.roster.model.StoredRow;
import com.blissfuljuan.die.roster.repository.RosterImportRepository;
import com.blissfuljuan.die.roster.view.CommitResult;
import com.blissfuljuan.die.roster.view.ImportDetail;
import com.blissfuljuan.die.roster.view.ImportView;
import com.blissfuljuan.die.roster.view.IssuedCredential;
import com.blissfuljuan.die.roster.view.RowView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

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

    private static final Set<String> ACTIONS = Set.of(CREATE, MATCH, SKIP_DUPLICATE, CONFLICT, INVALID);

    private final RosterImportRepository repo;
    private final RosterParser parser;
    private final RosterAnalyzer analyzer;
    private final AccessPolicy policy;
    private final ClassRepository classes;
    private final EnrollmentRules enrollmentRules;
    private final TempCredentialService tempCredentials;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final CommitLimiter limiter;
    private final CredentialHasher hasher;
    private final PlatformTransactionManager txManager;

    public RosterImportService(RosterImportRepository repo, RosterParser parser, RosterAnalyzer analyzer,
                               AccessPolicy policy, ClassRepository classes, EnrollmentRules enrollmentRules,
                               TempCredentialService tempCredentials, AuditService audit, ObjectMapper mapper,
                               CommitLimiter limiter, CredentialHasher hasher, PlatformTransactionManager txManager) {
        this.limiter = limiter;
        this.hasher = hasher;
        this.txManager = txManager;
        this.repo = repo;
        this.parser = parser;
        this.analyzer = analyzer;
        this.policy = policy;
        this.classes = classes;
        this.enrollmentRules = enrollmentRules;
        this.tempCredentials = tempCredentials;
        this.audit = audit;
        this.mapper = mapper;
    }

    // ---- upload / preview ----

    @Transactional
    public ImportView upload(AppUserDetails user, UUID classId, String rawFileName, byte[] bytes) {
        policy.requireClassManage(user, classId);

        // Parse (CPU/memory heavy, no database) before taking the class lock, so a slow or hostile file
        // cannot block enrollment and team changes for the whole class.
        String fileName = cleanFileName(rawFileName);
        List<NormalizedRow> parsed = parser.parse(fileName, bytes);
        assertWritable(classId);
        List<StoredRow> analyzed = analyzer.classify(classId, parsed, user);

        UUID id = repo.insertImport(classId, user.getId(), fileName, Tokens.sha256(bytes));
        analyzed.forEach(r -> repo.insertRow(id, r));
        audit.record(user.getId(), "ROSTER_IMPORT_PREVIEWED", "class", classId.toString(),
            Map.of("importId", id.toString(), "rows", analyzed.size()));
        return view(repo.find(id, false).orElseThrow());
    }

    @Transactional(readOnly = true)
    public List<ImportView> list(AppUserDetails user, UUID classId) {
        policy.requireClassManage(user, classId);
        return repo.listForClass(classId).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public ImportDetail detail(AppUserDetails user, UUID importId, String action, int page, int size) {
        ImportHeader h = header(user, importId, false);
        if (action != null && !ACTIONS.contains(action)) {
            throw bad("action must be one of " + ACTIONS);
        }
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 500);
        List<RowView> rows = repo.rows(importId, action, safePage, safeSize).stream().map(RosterImportService::rowView).toList();
        return new ImportDetail(view(h), rows, safePage, safeSize, repo.countRows(importId, action));
    }

    // ---- conflict resolution ----

    @Transactional
    public ImportView resolve(AppUserDetails user, UUID importId, List<ResolutionChange> changes) {
        ImportHeader h = header(user, importId, true);
        requirePreview(h);
        if (changes == null || changes.isEmpty()) throw bad("resolutions must not be empty");
        for (ResolutionChange c : changes) {
            StoredRow row = repo.row(importId, c.rowNumber()).orElseThrow(() -> bad("Row " + c.rowNumber() + " does not exist"));
            if (!CONFLICT.equals(row.action())) {
                throw bad("Row " + c.rowNumber() + " is not a conflict");
            }
            if (c.resolution() == null || !RosterRules.allowedResolutions(row.conflictKind()).contains(c.resolution())) {
                throw bad("Row " + c.rowNumber() + " (" + row.conflictKind() + ") accepts only "
                    + RosterRules.allowedResolutions(row.conflictKind()));
            }
            repo.setResolution(importId, c.rowNumber(), c.resolution());
        }
        audit.record(user.getId(), "ROSTER_CONFLICTS_RESOLVED", "class", h.classId().toString(),
            Map.of("importId", importId.toString(), "count", changes.size()));
        return view(h);
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
        ImportHeader h = header(user, importId, false);
        if ("COMMITTED".equals(h.status())) {
            return new Preflight(previouslyCommitted(h), 0);
        }
        requirePreview(h);
        return new Preflight(null, (int) repo.countRows(importId, CREATE));
    }

    private CommitResult commitLocked(AppUserDetails user, UUID importId, List<TempCredentialService.Prepared> prepared) {
        ImportHeader h = header(user, importId, true);
        if ("COMMITTED".equals(h.status())) {
            return previouslyCommitted(h);           // another commit won the race while we were hashing
        }
        requirePreview(h);
        UUID classId = h.classId();
        assertWritable(classId);

        List<StoredRow> stored = repo.allRows(importId);
        int unresolved = repo.summary(importId).unresolvedConflicts();
        if (unresolved > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, unresolved + " conflict(s) still need a resolution");
        }
        assertUnchangedSincePreview(classId, stored, user);
        if (stored.stream().filter(r -> CREATE.equals(r.action())).count() != prepared.size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The import changed while it was being prepared; try again");
        }

        int enrolledExisting = 0, skippedDuplicates = 0, skippedByResolution = 0, invalid = 0;
        List<StoredRow> createdRows = new ArrayList<>();
        List<UUID> createdIds = new ArrayList<>();
        for (StoredRow r : stored) {
            switch (r.action()) {
                case CREATE -> createdIds.add(createAccount(classId, r, user.getId(), createdRows));
                case MATCH -> enrolledExisting += enrollExisting(classId, r) ? 1 : 0;
                case SKIP_DUPLICATE -> skippedDuplicates++;
                case INVALID -> invalid++;
                case CONFLICT -> {
                    switch (r.resolution()) {
                        case USE_EXISTING -> enrolledExisting += enrollExisting(classId, r) ? 1 : 0;
                        case SKIP -> skippedByResolution++;
                        default -> throw new IllegalStateException("Unexpected resolution " + r.resolution());
                    }
                }
                default -> throw new IllegalStateException("Unexpected action " + r.action());
            }
        }

        List<TempCredentialService.Issued> issued = tempCredentials.storePrepared(createdIds, prepared, user.getId());
        List<IssuedCredential> credentials = new ArrayList<>(createdIds.size());
        for (int i = 0; i < createdIds.size(); i++) {
            StoredRow r = createdRows.get(i);
            credentials.add(new IssuedCredential(r.rowNumber(), createdIds.get(i), r.studentNumber(), r.lastName(),
                r.firstName(), issued.get(i).secret(), issued.get(i).expiresAt()));
        }

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("created", createdIds.size());
        counts.put("enrolledExisting", enrolledExisting);
        counts.put("skippedDuplicates", skippedDuplicates);
        counts.put("skippedByResolution", skippedByResolution);
        counts.put("invalid", invalid);
        repo.markCommitted(importId, user.getId(), json(counts));

        Map<String, Object> auditDetails = new LinkedHashMap<>(counts);
        auditDetails.put("importId", importId.toString());
        auditDetails.put("createdAccounts", createdIds.stream().map(UUID::toString).toList());
        audit.record(user.getId(), "ROSTER_IMPORT_COMMITTED", "class", classId.toString(), auditDetails);

        return new CommitResult(importId, false, createdIds.size(), enrolledExisting, skippedDuplicates,
            skippedByResolution, invalid, credentials);
    }

    @Transactional
    public void cancel(AppUserDetails user, UUID importId) {
        ImportHeader h = header(user, importId, true);
        if ("COMMITTED".equals(h.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A committed import cannot be cancelled");
        }
        if ("PREVIEW".equals(h.status())) {
            repo.setStatus(importId, "CANCELLED");
            audit.record(user.getId(), "ROSTER_IMPORT_CANCELLED", "class", h.classId().toString(),
                Map.of("importId", importId.toString()));
        }
    }

    // ---- helpers ----

    /**
     * The import must exist and the caller must manage its class. Imports are staff-only resources, so
     * every other case (missing, other class, enrolled student) is the identical 404.
     */
    private ImportHeader header(AppUserDetails user, UUID importId, boolean lock) {
        ImportHeader h = repo.find(importId, lock).orElseThrow(RosterImportService::importNotFound);
        try {
            policy.requireClassManage(user, h.classId());
        } catch (ResponseStatusException e) {
            throw importNotFound();
        }
        return h;
    }

    private static ApiException importNotFound() {
        return ApiException.notFound("IMPORT_NOT_FOUND", "Roster import not found");
    }

    private void assertWritable(UUID classId) {
        var cls = classes.lock(classId).orElseThrow(AccessPolicy::classNotFound);
        if ("ARCHIVED".equals(cls.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The class is archived and read-only");
        }
    }

    private static void requirePreview(ImportHeader h) {
        if (!"PREVIEW".equals(h.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The import is " + h.status().toLowerCase());
        }
    }

    private void assertUnchangedSincePreview(UUID classId, List<StoredRow> stored, AppUserDetails committer) {
        List<NormalizedRow> rebuilt = stored.stream().map(r -> new NormalizedRow(r.rowNumber(), r.studentNumber(),
            r.lastName(), r.firstName(), r.middleName(), r.email(), r.program(), r.section(),
            INVALID.equals(r.action()) ? r.message() : null)).toList();
        List<StoredRow> fresh = analyzer.classify(classId, rebuilt, committer);
        for (int i = 0; i < stored.size(); i++) {
            StoredRow was = stored.get(i), now = fresh.get(i);
            if (!was.action().equals(now.action()) || !Objects.equals(was.conflictKind(), now.conflictKind())
                || !Objects.equals(was.matchedUserId(), now.matchedUserId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Accounts or enrollments changed since the preview (row " + was.rowNumber()
                        + "); upload the file again");
            }
        }
    }

    private UUID createAccount(UUID classId, StoredRow r, UUID createdBy, List<StoredRow> createdRows) {
        UUID id = repo.insertPendingStudent(r.studentNumber(), r.lastName(), r.firstName(), r.middleName(), createdBy);
        enrollmentRules.enroll(classId, id, r.program(), r.section());
        createdRows.add(r);
        return id;
    }

    /** @return true if the student was newly enrolled or reinstated. */
    private boolean enrollExisting(UUID classId, StoredRow r) {
        return enrollmentRules.enroll(classId, r.matchedUserId(), r.program(), r.section())
            != EnrollmentRules.Outcome.ALREADY_ENROLLED;
    }

    private ImportView view(ImportHeader h) {
        return new ImportView(h.id(), h.classId(), h.fileName(), h.status(), h.createdAt(), h.committedAt(),
            repo.summary(h.id()));
    }

    private static RowView rowView(StoredRow r) {
        return new RowView(r.rowNumber(), r.studentNumber(), r.lastName(), r.firstName(), r.middleName(), r.email(),
            r.program(), r.section(), r.action(), r.conflictKind(), r.resolution(), r.message(),
            RosterRules.allowedResolutions(r.conflictKind()));
    }

    private CommitResult previouslyCommitted(ImportHeader h) {
        JsonNode n = read(h.summaryJson());
        return new CommitResult(h.id(), true, n.path("created").asInt(), n.path("enrolledExisting").asInt(),
            n.path("skippedDuplicates").asInt(), n.path("skippedByResolution").asInt(), n.path("invalid").asInt(),
            List.of());
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json == null ? "{}" : json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String cleanFileName(String raw) {
        String name = raw == null ? "" : raw;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1)
            .replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.isEmpty()) name = "roster";
        return name.length() > 200 ? name.substring(name.length() - 200) : name;
    }

    private static ResponseStatusException bad(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
