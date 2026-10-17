package com.blissfuljuan.dieapi.roster.service;

import com.blissfuljuan.dieapi.auth.model.AppUserDetails;
import com.blissfuljuan.dieapi.common.exception.ApiException;
import com.blissfuljuan.dieapi.common.exception.ErrorCode;
import com.blissfuljuan.dieapi.roster.model.ImportHeader;
import com.blissfuljuan.dieapi.roster.model.NormalizedRow;
import com.blissfuljuan.dieapi.roster.model.RowOutcome;
import com.blissfuljuan.dieapi.roster.model.StoredRow;
import com.blissfuljuan.dieapi.roster.view.CommitResult;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Helpers of the commit flow: stale-preview detection and the stored commit-summary JSON. */
@Component
public class RosterCommitSupport {

    private final RosterAnalyzer analyzer;
    private final ObjectMapper mapper;

    public RosterCommitSupport(RosterAnalyzer analyzer, ObjectMapper mapper) {
        this.analyzer = analyzer;
        this.mapper = mapper;
    }

    /** Re-classifies the stored rows against the current database; 409 IMPORT_STALE if any outcome changed. */
    public void assertUnchangedSincePreview(UUID classId, List<StoredRow> stored, AppUserDetails committer) {
        List<NormalizedRow> rebuilt = stored.stream().map(r -> new NormalizedRow(r.rowNumber(), r.studentNumber(),
            r.lastName(), r.firstName(), r.middleName(), r.email(), r.program(), r.section(),
            r.action() == RowOutcome.INVALID ? r.message() : null)).toList();
        List<StoredRow> fresh = analyzer.classify(classId, rebuilt, committer);
        for (int i = 0; i < stored.size(); i++) {
            StoredRow was = stored.get(i), now = fresh.get(i);
            if (was.action() != now.action() || was.conflictKind() != now.conflictKind()
                || !Objects.equals(was.matchedUserId(), now.matchedUserId())) {
                throw ApiException.conflict(ErrorCode.IMPORT_STALE,
                    "Accounts or enrollments changed since the preview (row " + was.rowNumber()
                        + "); upload the file again");
            }
        }
    }

    /** The result of an already committed import, rebuilt from its stored summary (never contains credentials). */
    public CommitResult previouslyCommitted(ImportHeader h) {
        JsonNode n = read(h.summaryJson());
        return new CommitResult(h.id(), true, n.path("created").asInt(), n.path("enrolledExisting").asInt(),
            n.path("skippedDuplicates").asInt(), n.path("skippedByResolution").asInt(), n.path("invalid").asInt(),
            List.of());
    }

    public String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json == null ? "{}" : json);
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }
}
