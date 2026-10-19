package com.blissfuljuan.dieapi.ops.service;

import com.blissfuljuan.dieapi.audit.model.AuditAction;
import com.blissfuljuan.dieapi.audit.model.AuditEvent;
import com.blissfuljuan.dieapi.audit.model.AuditResource;
import com.blissfuljuan.dieapi.common.exception.ApiException;
import com.blissfuljuan.dieapi.common.exception.ErrorCode;
import com.blissfuljuan.dieapi.messaging.service.OutboxService;
import com.blissfuljuan.dieapi.ops.model.TestJob;
import com.blissfuljuan.dieapi.ops.model.TestJobMessages;
import com.blissfuljuan.dieapi.ops.repository.TestJobRepository;
import com.blissfuljuan.dieapi.ops.view.TestJobView;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Infrastructure smoke test of the asynchronous backbone (not project content). Submitting writes the job (QUEUED) and
 * its outbox message in one transaction and returns at once; the relay, the workers and the result consumer do the rest.
 */
@Service
public class TestJobService {

    private final TestJobRepository jobs;
    private final OutboxService outbox;
    private final ApplicationEventPublisher events;
    private final ObjectMapper mapper;

    public TestJobService(TestJobRepository jobs, OutboxService outbox, ApplicationEventPublisher events,
                          ObjectMapper mapper) {
        this.jobs = jobs;
        this.outbox = outbox;
        this.events = events;
        this.mapper = mapper;
    }

    @Transactional
    public TestJobView submit(UUID actor, String note, boolean simulateFailure, String correlationId) {
        String cleanNote = note == null || note.isBlank() ? null : note.strip();
        String correlation = correlationId == null ? UUID.randomUUID().toString() : correlationId;
        UUID id = jobs.insert(actor, cleanNote, simulateFailure, correlation);
        Map<String, Object> payload = new HashMap<>();
        payload.put("testJobId", id.toString());
        payload.put("note", cleanNote);
        payload.put("simulateFailure", simulateFailure);
        UUID messageId = outbox.enqueue(TestJobMessages.AGGREGATE, id.toString(), TestJobMessages.JOBS,
            TestJobMessages.PING, correlation, payload);
        events.publishEvent(AuditEvent.of(actor, AuditAction.TEST_JOB_SUBMITTED, AuditResource.TEST_JOB, id.toString(),
            Map.of("messageId", messageId.toString(), "simulateFailure", simulateFailure)));
        return view(jobs.find(id).orElseThrow());
    }

    @Transactional(readOnly = true)
    public TestJobView get(UUID id) {
        return jobs.find(id).map(this::view)
            .orElseThrow(() -> ApiException.notFound(ErrorCode.TEST_JOB_NOT_FOUND, "That test job does not exist."));
    }

    private TestJobView view(TestJob job) {
        return new TestJobView(job.id(), job.state().name(), job.note(), job.simulateFailure(), job.correlationId(),
            parse(job.resultJson()), job.failureReason(), job.createdAt(), job.startedAt(), job.finishedAt());
    }

    private Map<String, Object> parse(String json) {
        if (json == null) return null;
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JacksonException e) {
            return null;
        }
    }
}
