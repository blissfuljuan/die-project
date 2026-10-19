package com.blissfuljuan.dieapi.ops.service;

import com.blissfuljuan.dieapi.audit.model.AuditAction;
import com.blissfuljuan.dieapi.audit.model.AuditEvent;
import com.blissfuljuan.dieapi.audit.model.AuditResource;
import com.blissfuljuan.dieapi.ops.model.StaleJob;
import com.blissfuljuan.dieapi.ops.repository.TestJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Reconciles test jobs whose result never arrived (a poison message dead-lettered before its job id could be read, a worker
 * lost mid-job, a broker outage longer than the retries): QUEUED/RUNNING longer than the threshold becomes FAILED with a
 * reason and an audit row. Postgres is authoritative, so this only needs the database: it is safe to run on every API
 * instance at once (rows are claimed with SKIP LOCKED in a single statement). Not an evaluation job reaper: real
 * evaluation jobs get leases and fencing in P3.
 */
@Service
public class StaleJobReaper {

    static final String REASON = "No result was received before the stale-job threshold; the job was failed by the reaper. "
        + "A late result is ignored.";

    private static final Logger log = LoggerFactory.getLogger(StaleJobReaper.class);

    private final TestJobRepository jobs;
    private final ApplicationEventPublisher events;
    private final long staleSeconds;
    private final int batchSize;

    public StaleJobReaper(TestJobRepository jobs, ApplicationEventPublisher events,
                          @Value("${die.ops.reaper.stale-after-seconds:900}") long staleSeconds,
                          @Value("${die.ops.reaper.batch-size:100}") int batchSize) {
        this.jobs = jobs;
        this.events = events;
        this.staleSeconds = staleSeconds;
        this.batchSize = batchSize;
    }

    /** One pass. @return the jobs that were failed */
    @Transactional
    public List<StaleJob> reapOnce() {
        List<StaleJob> reaped = jobs.failStale(staleSeconds, batchSize, REASON);
        for (StaleJob job : reaped) {
            events.publishEvent(AuditEvent.of(null, AuditAction.TEST_JOB_REAPED, AuditResource.TEST_JOB,
                job.id().toString(), Map.of("previousState", job.previousState().name(),
                    "ageSeconds", job.ageSeconds(), "thresholdSeconds", staleSeconds,
                    "correlationId", String.valueOf(job.correlationId()))));
            // Log with the job's own correlation id so the line joins the trail of the request that created it.
            MDC.put("correlationId", String.valueOf(job.correlationId()));
            try {
                log.warn("stale test job {} failed by the reaper (was {}, age {}s)", job.id(), job.previousState(), job.ageSeconds());
            } finally {
                MDC.remove("correlationId");
            }
        }
        return reaped;
    }
}
