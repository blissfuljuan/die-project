package com.blissfuljuan.die.orchestrator;

/** A message was not confirmed by the broker (unroutable, nacked, connection or timeout). */
public class PublishFailedException extends RuntimeException {

    public PublishFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
