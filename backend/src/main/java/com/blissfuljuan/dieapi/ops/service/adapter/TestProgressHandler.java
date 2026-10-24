package com.blissfuljuan.dieapi.ops.service.adapter;

import com.blissfuljuan.dieapi.messaging.model.Envelope;
import com.blissfuljuan.dieapi.messaging.service.port.InboundMessageHandler;
import com.blissfuljuan.dieapi.ops.model.TestJobMessages;
import com.blissfuljuan.dieapi.ops.repository.TestJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** {@code test.progress}: the orchestrator took the job, so QUEUED becomes RUNNING (ignored once the job moved on). */
@Component
public class TestProgressHandler implements InboundMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(TestProgressHandler.class);

    private final TestJobRepository jobs;

    public TestProgressHandler(TestJobRepository jobs) {
        this.jobs = jobs;
    }

    @Override
    public String messageType() {
        return TestJobMessages.PROGRESS;
    }

    @Override
    public void handle(Envelope envelope) {
        UUID jobId = UUID.fromString((String) envelope.payload().get("testJobId"));
        boolean moved = jobs.markRunning(jobId, envelope.messageId());
        log.info("test job {} progress {} (moved to RUNNING: {})", jobId, envelope.payload().get("stage"), moved);
    }
}
