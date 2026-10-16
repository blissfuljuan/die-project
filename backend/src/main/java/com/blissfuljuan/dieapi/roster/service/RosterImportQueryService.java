package com.blissfuljuan.dieapi.roster.service;

import com.blissfuljuan.dieapi.access.service.AccessPolicy;
import com.blissfuljuan.dieapi.auth.model.AppUserDetails;
import com.blissfuljuan.dieapi.common.exception.ApiException;
import com.blissfuljuan.dieapi.roster.model.ImportHeader;
import com.blissfuljuan.dieapi.roster.model.RowOutcome;
import com.blissfuljuan.dieapi.roster.model.StoredRow;
import com.blissfuljuan.dieapi.roster.model.rule.ConflictResolvers;
import com.blissfuljuan.dieapi.roster.repository.RosterImportRepository;
import com.blissfuljuan.dieapi.roster.view.ImportDetail;
import com.blissfuljuan.dieapi.roster.view.ImportView;
import com.blissfuljuan.dieapi.roster.view.RowView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Read side of roster imports (list, detail, projections) and the shared "import visible to this caller" check.
 * Imports are staff-only resources: every other case (missing, other class, enrolled student) is the identical 404.
 */
@Service
public class RosterImportQueryService {

    private final RosterImportRepository repo;
    private final AccessPolicy policy;
    private final ConflictResolvers resolvers;

    public RosterImportQueryService(RosterImportRepository repo, AccessPolicy policy, ConflictResolvers resolvers) {
        this.repo = repo;
        this.policy = policy;
        this.resolvers = resolvers;
    }

    @Transactional(readOnly = true)
    public List<ImportView> list(AppUserDetails user, UUID classId) {
        policy.requireClassManage(user, classId);
        return repo.listForClass(classId).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public ImportDetail detail(AppUserDetails user, UUID importId, String action, int page, int size) {
        ImportHeader h = header(user, importId, false);
        RowOutcome filter = RowOutcome.parse(action);
        if (action != null && filter == null) {
            throw ApiException.badRequest("INVALID_ACTION_FILTER",
                "action must be one of " + Arrays.toString(RowOutcome.values()));
        }
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 500);
        List<RowView> rows = repo.rows(importId, filter, safePage, safeSize).stream().map(this::rowView).toList();
        return new ImportDetail(view(h), rows, safePage, safeSize, repo.countRows(importId, filter));
    }

    /** The import must exist and the caller must manage its class; otherwise the identical 404. */
    ImportHeader header(AppUserDetails user, UUID importId, boolean lock) {
        ImportHeader h = repo.find(importId, lock).orElseThrow(RosterImportQueryService::importNotFound);
        try {
            policy.requireClassManage(user, h.classId());
        } catch (ApiException e) {
            throw importNotFound();
        }
        return h;
    }

    ImportView view(ImportHeader h) {
        return new ImportView(h.id(), h.classId(), h.fileName(), h.status().name(), h.createdAt(), h.committedAt(),
            repo.summary(h.id()));
    }

    private RowView rowView(StoredRow r) {
        List<String> allowed = r.conflictKind() == null ? List.of()
            : resolvers.forKind(r.conflictKind()).allowed().stream().map(Enum::name).toList();
        return new RowView(r.rowNumber(), r.studentNumber(), r.lastName(), r.firstName(), r.middleName(), r.email(),
            r.program(), r.section(), r.action().name(), r.conflictKind() == null ? null : r.conflictKind().name(),
            r.resolution() == null ? null : r.resolution().name(), r.message(), allowed);
    }

    private static ApiException importNotFound() {
        return ApiException.notFound("IMPORT_NOT_FOUND", "Roster import not found");
    }
}
