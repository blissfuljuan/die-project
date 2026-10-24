package com.blissfuljuan.dieapi.messaging.service.adapter;

import com.blissfuljuan.dieapi.messaging.model.BrokerStatus;
import com.blissfuljuan.dieapi.messaging.service.port.BrokerInspector;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Health and queue-depth questions over the API's shared broker connection. Every probe uses its own short-lived channel:
 * a passive declare of a missing queue closes the channel it ran on, and that must never touch the publisher's or the
 * consumer's channel.
 */
@Component
@ConditionalOnProperty(name = "die.messaging.enabled", havingValue = "true")
public class RabbitBrokerInspector implements BrokerInspector {

    private static final Logger log = LoggerFactory.getLogger(RabbitBrokerInspector.class);

    private final RabbitConnection connections;

    public RabbitBrokerInspector(RabbitConnection connections) {
        this.connections = connections;
    }

    @Override
    public BrokerStatus check() {
        long start = System.nanoTime();
        try {
            Connection connection = connections.get();
            try (Channel ignored = connection.createChannel()) {
                return new BrokerStatus(true, null, millisSince(start));
            }
        } catch (Exception e) {
            // The exception text can contain the host name; keep it in the log only.
            log.warn("broker health check failed: {}", e.toString());
            return new BrokerStatus(false, "broker unreachable", millisSince(start));
        }
    }

    @Override
    public Map<String, Long> queueDepths(Collection<String> queues) {
        Map<String, Long> depths = new LinkedHashMap<>();
        for (String queue : queues) {
            try {
                Connection connection = connections.get();
                try (Channel ch = connection.createChannel()) {
                    depths.put(queue, (long) ch.queueDeclarePassive(queue).getMessageCount());
                }
            } catch (Exception e) {
                log.debug("queue depth of {} unavailable: {}", queue, e.toString());
            }
        }
        return depths;
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
