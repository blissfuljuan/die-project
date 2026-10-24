package com.blissfuljuan.dieapi.ops.model;

import java.util.Set;

/**
 * Authoritative state of a test job. Transition table: QUEUED -> RUNNING -> SUCCEEDED | FAILED, and QUEUED -> SUCCEEDED | FAILED
 * when the result overtakes the progress message. Terminal states never change again, which is what makes duplicate
 * and reordered messages harmless.
 */
public enum TestJobState {
    QUEUED, RUNNING, SUCCEEDED, FAILED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED;
    }

    public Set<TestJobState> allowedNext() {
        return switch (this) {
            case QUEUED -> Set.of(RUNNING, SUCCEEDED, FAILED);
            case RUNNING -> Set.of(SUCCEEDED, FAILED);
            case SUCCEEDED, FAILED -> Set.of();
        };
    }
}
