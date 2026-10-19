package com.blissfuljuan.dieapi.messaging.service.adapter;

import com.blissfuljuan.dieapi.messaging.model.BrokerStatus;
import com.blissfuljuan.dieapi.messaging.service.port.BrokerInspector;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Contributor "broker" of the readiness group. Public endpoints show only UP/DOWN (show-details=never); nothing about hosts,
 * credentials or versions is added here either, so the detail is safe even if someone enables it.
 */
@Component("broker")
@ConditionalOnProperty(name = "die.messaging.enabled", havingValue = "true")
public class BrokerHealthIndicator implements HealthIndicator {

    private final BrokerInspector broker;

    public BrokerHealthIndicator(BrokerInspector broker) {
        this.broker = broker;
    }

    @Override
    public Health health() {
        BrokerStatus status = broker.check();
        return status.up() ? Health.up().build() : Health.down().build();
    }
}
