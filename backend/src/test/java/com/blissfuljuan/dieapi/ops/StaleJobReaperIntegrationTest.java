package com.blissfuljuan.dieapi.ops;

import com.blissfuljuan.dieapi.PostgresIntegrationTest;
import com.blissfuljuan.dieapi.ops.model.StaleJob;
import com.blissfuljuan.dieapi.ops.model.TestJobState;
import com.blissfuljuan.dieapi.ops.repository.TestJobRepository;
import com.blissfuljuan.dieapi.ops.service.StaleJobReaper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "die.ops.reaper.stale-after-seconds=600")
class StaleJobReaperIntegrationTest extends PostgresIntegrationTest {

    @Autowired StaleJobReaper reaper;
    @Autowired TestJobRepository jobs;
    @Autowired JdbcTemplate jdbc;

    private UUID insert(String state, int ageSeconds, String correlationId) {
        UUID id = jdbc.queryForObject("""
            INSERT INTO test_job (state, correlation_id, created_at, updated_at, started_at, finished_at)
            VALUES (?, ?, now() - make_interval(secs => ?), now() - make_interval(secs => ?),
                    CASE WHEN ? = 'QUEUED' THEN NULL ELSE now() - make_interval(secs => ?) END,
                    CASE WHEN ? IN ('SUCCEEDED', 'FAILED') THEN now() - make_interval(secs => ?) ELSE NULL END)
            RETURNING id
            """, UUID.class, state, correlationId, ageSeconds, ageSeconds, state, ageSeconds, state, ageSeconds);
        return id;
    }

    private String state(UUID id) {
        return jdbc.queryForObject("SELECT state FROM test_job WHERE id = ?", String.class, id);
    }

    @Test
    void staleQueuedAndRunningJobsAreFailedWithAReasonAndAnAuditRow() {
        UUID oldQueued = insert("QUEUED", 3_600, "corr-stale-queued");
        UUID oldRunning = insert("RUNNING", 3_600, "corr-stale-running");
        UUID freshQueued = insert("QUEUED", 30, "corr-fresh-queued");
        UUID freshRunning = insert("RUNNING", 120, "corr-fresh-running");
        UUID oldDone = insert("SUCCEEDED", 7_200, "corr-old-done");

        List<StaleJob> reaped = reaper.reapOnce();

        assertThat(reaped).extracting(StaleJob::id).containsExactlyInAnyOrder(oldQueued, oldRunning);
        assertThat(reaped).extracting(StaleJob::previousState)
            .containsExactlyInAnyOrder(TestJobState.QUEUED, TestJobState.RUNNING);
        assertThat(state(oldQueued)).isEqualTo("FAILED");
        assertThat(state(oldRunning)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT failure_reason FROM test_job WHERE id = ?", String.class, oldRunning))
            .contains("reaper");
        assertThat(jdbc.queryForObject("SELECT finished_at IS NOT NULL FROM test_job WHERE id = ?", Boolean.class, oldRunning)).isTrue();
        assertThat(state(freshQueued)).isEqualTo("QUEUED");
        assertThat(state(freshRunning)).isEqualTo("RUNNING");
        assertThat(state(oldDone)).isEqualTo("SUCCEEDED");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'TEST_JOB_REAPED'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
            SELECT details->>'previousState' FROM audit_log WHERE action = 'TEST_JOB_REAPED' AND resource_id = ?
            """, String.class, oldRunning.toString())).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT actor_user_id IS NULL FROM audit_log WHERE action = 'TEST_JOB_REAPED' LIMIT 1", Boolean.class))
            .as("a system action has no actor").isTrue();
    }

    @Test
    void aSecondPassFindsNothingAndALateResultCannotReviveTheJob() {
        UUID id = insert("RUNNING", 3_600, "corr-late-result");
        assertThat(reaper.reapOnce()).hasSize(1);
        assertThat(reaper.reapOnce()).isEmpty();

        boolean moved = jobs.finish(id, TestJobState.SUCCEEDED, "{}", null, UUID.randomUUID());

        assertThat(moved).as("terminal states never change again").isFalse();
        assertThat(state(id)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'TEST_JOB_REAPED'", Integer.class)).isEqualTo(1);
    }

    @Test
    void aJobThatFinishesNormallyBeforeTheReaperRunsIsLeftAlone() {
        UUID id = insert("RUNNING", 3_600, "corr-just-finished");
        jobs.finish(id, TestJobState.SUCCEEDED, "{}", null, UUID.randomUUID());

        assertThat(reaper.reapOnce()).isEmpty();
        assertThat(state(id)).isEqualTo("SUCCEEDED");
    }

    @Test
    void concurrentReapersFailEachStaleJobExactlyOnce() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 40; i++) ids.add(insert(i % 2 == 0 ? "QUEUED" : "RUNNING", 3_600, "corr-concurrent-" + i));

        int workers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<List<StaleJob>>> results = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                Callable<List<StaleJob>> pass = () -> {
                    go.await();
                    return reaper.reapOnce();
                };
                results.add(pool.submit(pass));
            }
            go.countDown();
            List<UUID> reapedIds = new ArrayList<>();
            for (Future<List<StaleJob>> f : results) f.get().forEach(j -> reapedIds.add(j.id()));

            assertThat(reapedIds).as("no job is reaped twice and none is missed").containsExactlyInAnyOrderElementsOf(ids);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'TEST_JOB_REAPED'", Integer.class)).isEqualTo(40);
    }
}
