package com.blissfuljuan.dieapi.ops.controller;

import com.blissfuljuan.dieapi.common.util.ProblemDetails;
import com.blissfuljuan.dieapi.ops.service.TestJobService;
import com.blissfuljuan.dieapi.ops.view.TestJobView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

/**
 * Operations smoke test of API, RabbitMQ, orchestrator, document worker and back. Administrators only; this is
 * infrastructure, not project content, so no class/project scoping applies.
 */
@RestController
@RequestMapping("/api/v1/ops/test-jobs")
@PreAuthorize("hasRole('ADMIN')")
public class TestJobController {

    public record SubmitTestJobRequest(@Size(max = 200) String note, Boolean simulateFailure) {}

    private final TestJobService jobs;

    public TestJobController(TestJobService jobs) {
        this.jobs = jobs;
    }

    @PostMapping
    public ResponseEntity<TestJobView> submit(@AuthenticationPrincipal(expression = "id") UUID actorId,
                                              @Valid @RequestBody(required = false) SubmitTestJobRequest body) {
        String note = body == null ? null : body.note();
        boolean simulate = body != null && Boolean.TRUE.equals(body.simulateFailure());
        TestJobView job = jobs.submit(actorId, note, simulate, MDC.get(ProblemDetails.REQUEST_ID_KEY));
        return ResponseEntity.accepted().location(URI.create("/api/v1/ops/test-jobs/" + job.id())).body(job);
    }

    @GetMapping("/{id}")
    public TestJobView get(@PathVariable UUID id) {
        return jobs.get(id);
    }
}
