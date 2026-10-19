package com.blissfuljuan.die.orchestrator;

/**
 * Queue naming convention and retry policy (docs/messaging.md; topology itself: infra/rabbitmq/definitions.json).
 * For queue Q in domain D: work exchange D, retry exchange D.retry (routing key Q.retry.N, TTL queues that dead-letter
 * back to D/Q), dead-letter exchange D.dlx (routing key Q, queue Q.dlq).
 */
public final class Topology {

    public record Destination(String exchange, String routingKey) {}

    /** What to do with a message whose delivery number {@code attempt} just failed. */
    public record Decision(boolean retry, int tier) {}

    public record Queue(String domain, String name, int tiers) {

        public int maxAttempts() {
            return tiers + 1;
        }

        public Decision decide(int attempt) {
            return attempt >= maxAttempts() ? new Decision(false, 0) : new Decision(true, attempt);
        }

        public Destination retryDestination(int tier) {
            return new Destination(domain + ".retry", name + ".retry." + tier);
        }

        public Destination deadLetterDestination() {
            return new Destination(domain + ".dlx", name);
        }
    }

    public static final String DOMAIN = "die.test";
    /** API -> orchestrator. */
    public static final Queue JOBS = new Queue(DOMAIN, "die.test.jobs", 2);
    /** Python worker -> orchestrator. */
    public static final Queue ANALYZE_RESULTS = new Queue(DOMAIN, "die.test.analyze.results", 2);
    /** Orchestrator -> Python worker (published, not consumed here). */
    public static final Destination ANALYZE = new Destination(DOMAIN, "die.test.analyze");
    /** Orchestrator -> API (published, not consumed here). */
    public static final Destination RESULTS = new Destination(DOMAIN, "die.test.results");

    private Topology() {}
}
