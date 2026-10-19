package com.blissfuljuan.die.orchestrator;

import java.util.Map;

/** Port: publish one persistent message and return only after the broker confirmed it. */
public interface Publisher {

    void publish(Topology.Destination destination, byte[] body, String messageId, String correlationId, String type,
                 Map<String, Object> headers);
}
