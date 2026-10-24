package com.blissfuljuan.dieapi.ops.repository;

import com.blissfuljuan.dieapi.ops.model.JobStats;
import com.blissfuljuan.dieapi.ops.model.OutboxStats;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Read-only counters for the administrator health report. */
@Repository
public class PlatformStatsRepository {

    private final JdbcClient jdbc;

    public PlatformStatsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Round trip to Postgres. @throws org.springframework.dao.DataAccessException when the database cannot answer */
    public void ping() {
        jdbc.sql("SELECT 1").query(Integer.class).single();
    }

    public OutboxStats outbox() {
        return jdbc.sql("""
                SELECT count(*) FILTER (WHERE status = 'PENDING') AS pending,
                       count(*) FILTER (WHERE status = 'FAILED') AS failed,
                       floor(extract(epoch FROM now() - min(created_at) FILTER (WHERE status = 'PENDING')))::bigint AS oldest
                FROM outbox_event
                """)
            .query((rs, n) -> new OutboxStats(rs.getLong("pending"), rs.getLong("failed"),
                rs.getObject("oldest") == null ? null : rs.getLong("oldest")))
            .single();
    }

    public JobStats testJobs(long staleSeconds) {
        return jdbc.sql("""
                SELECT count(*) FILTER (WHERE state = 'QUEUED') AS queued,
                       count(*) FILTER (WHERE state = 'RUNNING') AS running,
                       count(*) FILTER (WHERE state = 'SUCCEEDED') AS succeeded,
                       count(*) FILTER (WHERE state = 'FAILED') AS failed,
                       count(*) FILTER (WHERE state IN ('QUEUED', 'RUNNING')
                                          AND updated_at < now() - make_interval(secs => :stale)) AS overdue
                FROM test_job
                """)
            .param("stale", staleSeconds)
            .query((rs, n) -> new JobStats(rs.getLong("queued"), rs.getLong("running"), rs.getLong("succeeded"),
                rs.getLong("failed"), rs.getLong("overdue")))
            .single();
    }
}
