package com.blissfuljuan.dieapi.ops.model;

import com.blissfuljuan.dieapi.messaging.model.Destination;

/** Message types and destinations of the test job round trip (docs/messaging.md). */
public final class TestJobMessages {

    public static final String PING = "test.ping";
    public static final String PROGRESS = "test.progress";
    public static final String RESULT = "test.result";
    public static final String AGGREGATE = "test_job";
    /** API -> orchestrator (queue die.test.jobs). */
    public static final Destination JOBS = new Destination("die.test", "die.test.jobs");

    private TestJobMessages() {}
}
