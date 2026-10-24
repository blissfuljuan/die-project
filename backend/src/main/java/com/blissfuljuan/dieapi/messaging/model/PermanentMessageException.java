package com.blissfuljuan.dieapi.messaging.model;

/** Handling this message can never succeed (unknown type, unknown job): dead-letter it instead of retrying. */
public class PermanentMessageException extends RuntimeException {

    public PermanentMessageException(String message) {
        super(message);
    }
}
