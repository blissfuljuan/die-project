package com.blissfuljuan.dieapi.messaging.service.port;

import com.blissfuljuan.dieapi.messaging.model.OutboundMessage;

/** Port: puts one persistent message on the broker and returns only after the broker confirmed it. */
public interface MessagePublisher {

    /** @throws com.blissfuljuan.dieapi.messaging.model.PublishFailedException not confirmed, unroutable or unreachable */
    void publish(OutboundMessage message);
}
