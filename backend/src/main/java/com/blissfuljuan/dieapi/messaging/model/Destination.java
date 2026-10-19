package com.blissfuljuan.dieapi.messaging.model;

/** Where a message is published: an exchange and a routing key (topology: infra/rabbitmq/definitions.json). */
public record Destination(String exchange, String routingKey) {}
