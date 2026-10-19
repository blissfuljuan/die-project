package com.blissfuljuan.dieapi.messaging.service;

import com.blissfuljuan.dieapi.messaging.model.Envelope;
import com.blissfuljuan.dieapi.messaging.model.PermanentMessageException;
import com.blissfuljuan.dieapi.messaging.repository.InboxRepository;
import com.blissfuljuan.dieapi.messaging.service.port.InboundMessageHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Idempotent dispatch of one inbound message to the module that owns its type. The dedupe row (consumer, messageId)
 * and the handler's writes share one transaction: either both commit or neither does, and a duplicate delivery finds
 * the row and is a no-op.
 */
@Service
public class InboundProcessor {

    private static final Logger log = LoggerFactory.getLogger(InboundProcessor.class);

    private final InboxRepository inbox;
    private final Map<String, InboundMessageHandler> handlers = new HashMap<>();

    public InboundProcessor(InboxRepository inbox, List<InboundMessageHandler> registered) {
        this.inbox = inbox;
        for (InboundMessageHandler handler : registered) {
            if (handlers.put(handler.messageType(), handler) != null) {
                throw new IllegalStateException("Two handlers for message type " + handler.messageType());
            }
        }
    }

    /** @return true if the message was handled now, false if it was a duplicate */
    @Transactional
    public boolean process(String consumer, Envelope envelope) {
        InboundMessageHandler handler = handlers.get(envelope.type());
        if (handler == null) {
            throw new PermanentMessageException("No handler for message type " + envelope.type());
        }
        if (!inbox.recordIfNew(consumer, envelope.messageId())) {
            log.info("duplicate delivery ignored (type {})", envelope.type());
            return false;
        }
        handler.handle(envelope);
        return true;
    }
}
