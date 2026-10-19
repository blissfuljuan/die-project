package com.blissfuljuan.die.orchestrator;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

/**
 * The worker's single broker connection, created lazily and replaced when found closed. Reconnect policy is owned by
 * the callers (publisher: next message; consumers: the watchdog), so there is exactly one place that recreates
 * consumers. amqp-client's automatic recovery is off on purpose: after a recovery, stale delivery tags would make the
 * broker close the channel.
 */
public final class BrokerConnection implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BrokerConnection.class);

    private final ConnectionFactory factory = new ConnectionFactory();
    private Connection connection;

    public BrokerConnection(Settings settings) {
        factory.setHost(settings.host());
        factory.setPort(settings.port());
        factory.setUsername(settings.username());
        factory.setPassword(settings.password());
        factory.setVirtualHost(settings.virtualHost());
        factory.setAutomaticRecoveryEnabled(false);
        factory.setTopologyRecoveryEnabled(false);
        factory.setRequestedHeartbeat(30);
        factory.setConnectionTimeout(5_000);
        factory.setHandshakeTimeout(5_000);
        factory.setShutdownTimeout(5_000);
    }

    public synchronized Connection get() throws IOException, TimeoutException {
        if (connection == null || !connection.isOpen()) {
            connection = factory.newConnection("die-orchestrator");
            log.info("connected to RabbitMQ");
        }
        return connection;
    }

    public synchronized boolean isOpen() {
        return connection != null && connection.isOpen();
    }

    @Override
    public synchronized void close() {
        if (connection != null && connection.isOpen()) {
            try {
                connection.close(3_000);
            } catch (IOException e) {
                log.debug("closing the connection failed", e);
            }
        }
    }
}
