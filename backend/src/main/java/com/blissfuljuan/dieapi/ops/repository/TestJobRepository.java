package com.blissfuljuan.dieapi.ops.repository;

import com.blissfuljuan.dieapi.ops.model.StaleJob;
import com.blissfuljuan.dieapi.ops.model.TestJob;
import com.blissfuljuan.dieapi.ops.model.TestJobState;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** All SQL of test_job. Every transition is a guarded UPDATE, so concurrent and repeated messages cannot move a job backwards. */
@Repository
public class TestJobRepository {

    private final JdbcClient jdbc;

    public TestJobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insert(UUID requestedBy, String note, boolean simulateFailure, String correlationId) {
        return jdbc.sql("""
                INSERT INTO test_job (requested_by, note, simulate_failure, correlation_id)
                VALUES (:by, :note, :sim, :cid) RETURNING id
                """)
            .param("by", requestedBy).param("note", note).param("sim", simulateFailure).param("cid", correlationId)
            .query(UUID.class).single();
    }

    public Optional<TestJob> find(UUID id) {
        return jdbc.sql("""
                SELECT id, state, note, simulate_failure, requested_by, correlation_id, result::text AS result,
                       failure_reason, created_at, started_at, finished_at
                FROM test_job WHERE id = :id
                """)
            .param("id", id)
            .query((rs, n) -> new TestJob(rs.getObject("id", UUID.class), TestJobState.valueOf(rs.getString("state")),
                rs.getString("note"), rs.getBoolean("simulate_failure"), rs.getObject("requested_by", UUID.class),
                rs.getString("correlation_id"), rs.getString("result"), rs.getString("failure_reason"),
                rs.getTimestamp("created_at").toInstant(), instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("finished_at"))))
            .optional();
    }

    /** QUEUED -> RUNNING. @return true if the job moved */
    public boolean markRunning(UUID id, UUID messageId) {
        return jdbc.sql("""
                UPDATE test_job SET state = 'RUNNING', started_at = now(), updated_at = now(), last_message_id = :m
                WHERE id = :id AND state = 'QUEUED'
                """).param("id", id).param("m", messageId).update() == 1;
    }

    /** QUEUED|RUNNING -> SUCCEEDED|FAILED. @return true if the job moved (false: already finished, e.g. a late duplicate) */
    public boolean finish(UUID id, TestJobState terminal, String resultJson, String failureReason, UUID messageId) {
        return jdbc.sql("""
                UPDATE test_job SET state = :s, result = CAST(:result AS jsonb), failure_reason = :reason,
                       finished_at = now(), updated_at = now(), last_message_id = :m
                WHERE id = :id AND state IN ('QUEUED', 'RUNNING')
                """)
            .param("id", id).param("s", terminal.name()).param("result", resultJson).param("reason", failureReason)
            .param("m", messageId).update() == 1;
    }

    /**
     * Fails QUEUED/RUNNING jobs that have not changed for {@code staleSeconds}. One statement: the candidates are locked with
     * FOR UPDATE SKIP LOCKED, so two API instances (or a reaper pass racing a late result) never touch the same row twice, and
     * the state guard keeps a job that just finished untouched. A result that arrives later finds a terminal job and is a no-op.
     */
    public List<StaleJob> failStale(long staleSeconds, int limit, String reason) {
        return jdbc.sql("""
                WITH stale AS (
                    SELECT id, state AS previous_state
                    FROM test_job
                    WHERE state IN ('QUEUED', 'RUNNING') AND updated_at < now() - make_interval(secs => :stale)
                    ORDER BY updated_at
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED)
                UPDATE test_job t SET state = 'FAILED', failure_reason = :reason, finished_at = now(), updated_at = now()
                FROM stale s
                WHERE t.id = s.id
                RETURNING t.id, s.previous_state, t.correlation_id,
                          floor(extract(epoch FROM now() - t.created_at))::bigint AS age_seconds
                """)
            .param("stale", staleSeconds).param("limit", limit).param("reason", reason)
            .query((rs, n) -> new StaleJob(rs.getObject("id", UUID.class),
                TestJobState.valueOf(rs.getString("previous_state")), rs.getString("correlation_id"),
                rs.getLong("age_seconds")))
            .list();
    }

    private static java.time.Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
