package com.blissfuljuan.dieapi.messaging.service.adapter;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

/**
 * The API's single broker connection. Created lazily, so the application starts (and stays healthy) while the
 * broker is down; replaced when it is found closed. Reconnect policy is owned by the callers: the publisher
 * opens a fresh channel on its next message, the consumer's watchdog re-subscribes every few seconds. (amqp-client's
 * automatic recovery is switched off on purpose so there is exactly one place that recreates consumers.)
 */
@Component
@ConditionalOnProperty(name = "die.messaging.enabled", havingValue = "true")
public class RabbitConnection {

    private static final Logger log = LoggerFactory.getLogger(RabbitConnection.class);

    private final ConnectionFactory factory = new ConnectionFactory();
    private Connection connection;

    public RabbitConnection(@Value("${die.messaging.host}") String host,
                            @Value("${die.messaging.port:5672}") int port,
                            @Value("${die.messaging.username}") String username,
                            @Value("${die.messaging.password:}") String password,
                            @Value("${die.messaging.virtual-host:/}") String virtualHost) {
        factory.setHost(host);
        factory.setPort(port);
        factory.setUsername(username);
        factory.setPassword(password);
        factory.setVirtualHost(virtualHost);
        factory.setAutomaticRecoveryEnabled(false);
        factory.setTopologyRecoveryEnabled(false);
        factory.setRequestedHeartbeat(30);
        factory.setConnectionTimeout(5_000);
        factory.setHandshakeTimeout(5_000);
        factory.setShutdownTimeout(5_000);
    }

    public synchronized Connection get() throws IOException, TimeoutException {
        if (connection == null || !connection.isOpen()) {
            connection = factory.newConnection("die-api");
            log.info("connected to RabbitMQ");
        }
        return connection;
    }

    @PreDestroy
    synchronized void close() {
        if (connection != null && connection.isOpen()) {
            try {
                connection.close(3_000);
            } catch (IOException e) {
                log.debug("closing RabbitMQ connection failed", e);
            }
        }
    }
}
