package com.blissfuljuan.die.orchestrator;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** Publishes persistent, mandatory messages on a confirm channel; returns after the broker's ack. Any failure discards the channel. */
public final class ConfirmingPublisher implements Publisher, AutoCloseable {

    private final BrokerConnection connection;
    private final long confirmTimeoutMillis;
    private final Object lock = new Object();
    private final AtomicReference<String> returned = new AtomicReference<>();
    private Channel channel;

    public ConfirmingPublisher(BrokerConnection connection, long confirmTimeoutMillis) {
        this.connection = connection;
        this.confirmTimeoutMillis = confirmTimeoutMillis;
    }

    @Override
    public void publish(Topology.Destination destination, byte[] body, String messageId, String correlationId,
                        String type, Map<String, Object> headers) {
        synchronized (lock) {
            try {
                Channel ch = channel();
                returned.set(null);
                ch.basicPublish(destination.exchange(), destination.routingKey(), true,
                    properties(messageId, correlationId, type, headers), body);
                boolean acked = ch.waitForConfirms(confirmTimeoutMillis);
                if (returned.get() != null) {
                    throw new PublishFailedException("Broker could not route the message: " + returned.get(), null);
                }
                if (!acked) {
                    discard();
                    throw new PublishFailedException("Broker negatively acknowledged the message", null);
                }
            } catch (PublishFailedException e) {
                throw e;
            } catch (IOException | TimeoutException | RuntimeException e) {
                discard();
                throw new PublishFailedException("Broker unavailable: " + e.getClass().getSimpleName(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                discard();
                throw new PublishFailedException("Interrupted while waiting for the broker", e);
            }
        }
    }

    private Channel channel() throws IOException, TimeoutException {
        if (channel == null || !channel.isOpen()) {
            Channel ch = connection.get().createChannel();
            ch.confirmSelect();
            ch.addReturnListener(r -> returned.set(r.getExchange() + "/" + r.getRoutingKey() + ": " + r.getReplyText()));
            channel = ch;
        }
        return channel;
    }

    private void discard() {
        if (channel != null) {
            try {
                channel.abort();
            } catch (IOException | RuntimeException ignored) {
                // already gone
            }
            channel = null;
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            discard();
        }
    }

    private static AMQP.BasicProperties properties(String messageId, String correlationId, String type,
                                                   Map<String, Object> headers) {
        AMQP.BasicProperties.Builder b = new AMQP.BasicProperties.Builder()
            .contentType("application/json").contentEncoding("utf-8").deliveryMode(2)
            .timestamp(new Date()).appId("die-orchestrator").headers(new HashMap<>(headers));
        if (messageId != null) b.messageId(messageId);
        if (correlationId != null) b.correlationId(correlationId);
        if (type != null) b.type(type);
        return b.build();
    }
}
