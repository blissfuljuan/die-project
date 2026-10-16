package com.blissfuljuan.dieapi.auth.model.rule;

/**
 * Time-window throttle: within {@code windowMinutes}, an identifier is blocked after
 * {@code maxPerIdentifier} failures and a client address after {@code maxPerIp} failures. Pure data and
 * rule; counting is the repository's job. This is not the same concept as the per-credential lockout after
 * N wrong temporary secrets, which is a persistent counter with no window.
 */
public record ThrottlePolicy(int maxPerIdentifier, int maxPerIp, int windowMinutes) {

    /** True when either budget is used up. */
    public boolean blocks(long failuresForIdentifier, long failuresForIp) {
        return failuresForIdentifier >= maxPerIdentifier || failuresForIp >= maxPerIp;
    }
}
