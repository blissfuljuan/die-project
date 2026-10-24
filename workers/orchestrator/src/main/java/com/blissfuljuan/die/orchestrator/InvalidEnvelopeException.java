package com.blissfuljuan.die.orchestrator;

import java.util.List;

/** The bytes are not a valid envelope. Never retried: goes to the DLQ. */
public class InvalidEnvelopeException extends RuntimeException {

    private final List<String> problems;

    public InvalidEnvelopeException(String message, List<String> problems) {
        super(message);
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
