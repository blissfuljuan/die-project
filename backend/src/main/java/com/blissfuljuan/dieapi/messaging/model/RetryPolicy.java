package com.blissfuljuan.dieapi.messaging.model;

/**
 * Bounded retry with backoff. The retry queues {@code Q.retry.1..tiers} have increasing TTLs (5 s, 30 s), so attempt
 * {@code n} that fails waits in tier {@code n}; after {@code maxAttempts} the message is dead-lettered.
 */
public record RetryPolicy(int tiers) {

    public static final RetryPolicy DEFAULT = new RetryPolicy(2);

    public enum Action { RETRY, DEAD_LETTER }

    public record Decision(Action action, int tier) {}

    public int maxAttempts() {
        return tiers + 1;
    }

    /** What to do with a message whose delivery number {@code attempt} (1-based) just failed. */
    public Decision decide(int attempt) {
        return attempt >= maxAttempts() ? new Decision(Action.DEAD_LETTER, 0) : new Decision(Action.RETRY, attempt);
    }
}
