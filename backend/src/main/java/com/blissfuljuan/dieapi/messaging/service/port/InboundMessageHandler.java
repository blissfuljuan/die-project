package com.blissfuljuan.dieapi.messaging.service.port;

import com.blissfuljuan.dieapi.messaging.model.Envelope;

/**
 * Port: a module that wants to react to one message type implements this (in its own {@code service/adapter}).
 * It runs inside the inbox transaction, so its writes and the dedupe row commit together. Throw
 * {@link com.blissfuljuan.dieapi.messaging.model.PermanentMessageException} when retrying cannot help.
 */
public interface InboundMessageHandler {

    String messageType();

    void handle(Envelope envelope);
}
