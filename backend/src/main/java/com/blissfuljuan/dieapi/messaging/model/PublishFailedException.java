package com.blissfuljuan.dieapi.messaging.model;

/**
 * A message could not be handed to the broker. {@code brokerUnavailable} separates "the broker could not be
 * reached or did not answer in time" (retry later, never gives up) from "the broker refused or could not route it"
 * (counts against the bounded attempts).
 */
public class PublishFailedException extends RuntimeException {

    private final boolean brokerUnavailable;

    public PublishFailedException(String message, boolean brokerUnavailable, Throwable cause) {
        super(message, cause);
        this.brokerUnavailable = brokerUnavailable;
    }

    public boolean isBrokerUnavailable() {
        return brokerUnavailable;
    }
}
