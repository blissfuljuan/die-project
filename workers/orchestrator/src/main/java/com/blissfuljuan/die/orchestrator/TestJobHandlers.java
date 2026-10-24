package com.blissfuljuan.die.orchestrator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The two orchestration steps of the connectivity test job. */
public final class TestJobHandlers {

    public static final String WORKER = "orchestrator";

    private TestJobHandlers() {}

    /** die.test.jobs: test.ping from the API -> test.progress (RUNNING) to the API and test.analyze to the document worker. */
    public static final class Ping implements Handler {
        @Override
        public List<Outgoing> handle(Envelope in) {
            if (!"test.ping".equals(in.type())) {
                throw new PermanentFailure("Unsupported message type " + in.type());
            }
            Map<String, Object> progress = new LinkedHashMap<>();
            progress.put("testJobId", in.payload().get("testJobId"));
            progress.put("stage", "orchestrator-started");
            progress.put("worker", WORKER);
            Map<String, Object> analyze = new LinkedHashMap<>(in.payload());   // testJobId, note, simulateFailure
            return List.of(
                new Outgoing(Topology.RESULTS, in.derive("progress", "test.progress", progress)),
                new Outgoing(Topology.ANALYZE, in.derive("forward", "test.analyze", analyze)));
        }

        @Override
        public List<Outgoing> onGiveUp(Envelope in, String reason) {
            return failed(in, reason);
        }
    }

    /** die.test.analyze.results: test.analyze.result from the Python worker -> test.result to the API. */
    public static final class AnalyzeResult implements Handler {
        @Override
        public List<Outgoing> handle(Envelope in) {
            if (!"test.analyze.result".equals(in.type())) {
                throw new PermanentFailure("Unsupported message type " + in.type());
            }
            Map<String, Object> p = in.payload();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("testJobId", p.get("testJobId"));
            result.put("outcome", p.get("outcome"));
            result.put("worker", WORKER);
            List<Object> chain = new ArrayList<>(List.of(WORKER));
            chain.add(p.get("worker"));
            result.put("workers", chain);
            if (p.get("reason") != null) result.put("reason", p.get("reason"));
            if (p.get("result") != null) result.put("result", p.get("result"));
            return List.of(new Outgoing(Topology.RESULTS, in.derive("result", "test.result", result)));
        }

        @Override
        public List<Outgoing> onGiveUp(Envelope in, String reason) {
            return failed(in, reason);
        }
    }

    private static List<Handler.Outgoing> failed(Envelope in, String reason) {
        Object jobId = in.payload().get("testJobId");
        if (jobId == null) {
            return List.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("testJobId", jobId);
        result.put("outcome", "FAILED");
        result.put("worker", WORKER);
        result.put("workers", List.of(WORKER));
        result.put("reason", reason.length() > 500 ? reason.substring(0, 500) : reason);
        return List.of(new Handler.Outgoing(Topology.RESULTS, in.derive("failed", "test.result", result)));
    }
}
