package com.blissfuljuan.dieapi.ops.service.adapter;

import com.blissfuljuan.dieapi.messaging.model.Envelope;
import com.blissfuljuan.dieapi.messaging.model.PermanentMessageException;
import com.blissfuljuan.dieapi.messaging.service.port.InboundMessageHandler;
import com.blissfuljuan.dieapi.ops.model.TestJobMessages;
import com.blissfuljuan.dieapi.ops.model.TestJobState;
import com.blissfuljuan.dieapi.ops.repository.TestJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * {@code test.result}: the chain finished. The guarded UPDATE only moves a job that is not finished yet, so a duplicate
 * or a late contradicting result changes nothing. An unknown job id is a permanent error (dead-lettered).
 */
@Component
public class TestResultHandler implements InboundMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(TestResultHandler.class);

    private final TestJobRepository jobs;
    private final ObjectMapper mapper;

    public TestResultHandler(TestJobRepository jobs, ObjectMapper mapper) {
        this.jobs = jobs;
        this.mapper = mapper;
    }

    @Override
    public String messageType() {
        return TestJobMessages.RESULT;
    }

    @Override
    public void handle(Envelope envelope) {
        Map<String, Object> p = envelope.payload();
        UUID jobId = UUID.fromString((String) p.get("testJobId"));
        if (jobs.find(jobId).isEmpty()) {
            throw new PermanentMessageException("Result for unknown test job " + jobId);
        }
        TestJobState outcome = TestJobState.valueOf((String) p.get("outcome"));
        Map<String, Object> document = new HashMap<>();
        document.put("worker", p.get("worker"));
        document.put("workers", p.get("workers"));
        document.put("result", p.get("result"));
        String json;
        try {
            json = mapper.writeValueAsString(document);
        } catch (JacksonException e) {
            throw new PermanentMessageException("Result document is not serializable");
        }
        boolean moved = jobs.finish(jobId, outcome, json, (String) p.get("reason"), envelope.messageId());
        log.info("test job {} result {} (state changed: {})", jobId, outcome, moved);
    }
}
