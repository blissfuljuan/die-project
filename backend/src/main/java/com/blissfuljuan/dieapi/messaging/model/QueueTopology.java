package com.blissfuljuan.dieapi.messaging.model;

/**
 * Naming convention of one work queue (docs/messaging.md): for queue {@code Q} in domain {@code D}
 * the work exchange is {@code D}, retries go to exchange {@code D.retry} with routing key {@code Q.retry.N}
 * (queue {@code Q.retry.N} has a TTL and dead-letters back to {@code D}/{@code Q}), and exhausted or poison
 * messages go to exchange {@code D.dlx} with routing key {@code Q} (queue {@code Q.dlq}).
 */
public record QueueTopology(String domain, String queue, RetryPolicy retry) {

    public static QueueTopology of(String domain, String queue) {
        return new QueueTopology(domain, queue, RetryPolicy.DEFAULT);
    }

    public Destination retryDestination(int tier) {
        return new Destination(domain + ".retry", queue + ".retry." + tier);
    }

    public Destination deadLetterDestination() {
        return new Destination(domain + ".dlx", queue);
    }
}
