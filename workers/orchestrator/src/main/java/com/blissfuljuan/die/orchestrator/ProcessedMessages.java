package com.blissfuljuan.die.orchestrator;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded memory of fully handled (queue, messageId) pairs. Enough for this stateless slice: after a restart a duplicate
 * is handled again, and since the messages it derives have deterministic ids, the consumers downstream drop them. The
 * durable job-id + step-id dedupe in Postgres arrives with the first stage that has side effects (P3).
 */
public final class ProcessedMessages {

    private final Map<String, Boolean> seen;

    public ProcessedMessages(int capacity) {
        this.seen = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > capacity;
            }
        };
    }

    public synchronized boolean seen(String queue, String messageId) {
        return seen.get(queue + "|" + messageId) != null;
    }

    public synchronized void remember(String queue, String messageId) {
        seen.put(queue + "|" + messageId, Boolean.TRUE);
    }
}
