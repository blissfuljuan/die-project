package com.blissfuljuan.dieapi.messaging.service.adapter;

import com.blissfuljuan.dieapi.messaging.model.OutboundMessage;
import com.blissfuljuan.dieapi.messaging.model.PublishFailedException;
import com.blissfuljuan.dieapi.messaging.service.port.MessagePublisher;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Publishes persistent, mandatory messages on a confirm channel and returns only after the broker's ack. An
 * unroutable message (basic.return) or a nack is a refusal; anything that stops the exchange with the broker
 * (connection, channel, timeout) is "unavailable". After any failure the channel is discarded, so the next call
 * reconnects.
 */
@Component
@ConditionalOnProperty(name = "die.messaging.enabled", havingValue = "true")
public class RabbitMessagePublisher implements MessagePublisher {

    private final RabbitConnection connections;
    private final long confirmTimeoutMillis;
    private final Object lock = new Object();
    private Channel channel;
    private final AtomicReference<String> returned = new AtomicReference<>();

    public RabbitMessagePublisher(RabbitConnection connections,
                                  @Value("${die.messaging.confirm-timeout-ms:5000}") long confirmTimeoutMillis) {
        this.connections = connections;
        this.confirmTimeoutMillis = confirmTimeoutMillis;
    }

    @Override
    public void publish(OutboundMessage message) {
        synchronized (lock) {
            try {
                Channel ch = channel();
                returned.set(null);
                ch.basicPublish(message.destination().exchange(), message.destination().routingKey(), true,
                    properties(message), message.body());
                boolean acked = ch.waitForConfirms(confirmTimeoutMillis);
                if (returned.get() != null) {
                    throw new PublishFailedException("Broker could not route the message: " + returned.get(), false, null);
                }
                if (!acked) {
                    discard();
                    throw new PublishFailedException("Broker negatively acknowledged the message", false, null);
                }
            } catch (PublishFailedException e) {
                throw e;
            } catch (IOException | TimeoutException | RuntimeException e) {
                discard();
                throw new PublishFailedException("Broker unavailable: " + e.getClass().getSimpleName(), true, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                discard();
                throw new PublishFailedException("Interrupted while waiting for the broker", true, e);
            }
        }
    }

    private Channel channel() throws IOException, TimeoutException {
        if (channel == null || !channel.isOpen()) {
            Channel ch = connections.get().createChannel();
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

    @PreDestroy
    void close() {
        synchronized (lock) {
            discard();
        }
    }

    private static AMQP.BasicProperties properties(OutboundMessage m) {
        Map<String, Object> headers = new HashMap<>(m.headers());
        AMQP.BasicProperties.Builder b = new AMQP.BasicProperties.Builder()
            .contentType("application/json").contentEncoding("utf-8").deliveryMode(2)
            .timestamp(new Date()).appId("die-api").headers(headers);
        if (m.messageId() != null) b.messageId(m.messageId().toString());
        if (m.correlationId() != null) b.correlationId(m.correlationId());
        if (m.type() != null) b.type(m.type());
        return b.build();
    }
}
