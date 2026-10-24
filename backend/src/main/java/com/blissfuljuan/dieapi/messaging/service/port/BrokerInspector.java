package com.blissfuljuan.dieapi.messaging.service.port;

import com.blissfuljuan.dieapi.messaging.model.BrokerStatus;

import java.util.Collection;
import java.util.Map;

/** Port: read-only questions about the broker (health and queue depth). Never publishes or consumes. */
public interface BrokerInspector {

    /** Can the API open a connection and a channel right now? Never throws. */
    BrokerStatus check();

    /**
     * Ready-message count per queue (queue name -> messages). A queue that does not exist or cannot be read is
     * absent from the result. Never throws.
     */
    Map<String, Long> queueDepths(Collection<String> queues);
}
