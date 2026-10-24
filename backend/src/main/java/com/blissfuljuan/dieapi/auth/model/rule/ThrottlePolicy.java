package com.blissfuljuan.dieapi.auth.model.rule;

/**
 * Time-window throttle. Within {@code windowMinutes}:
 * <ul>
 *   <li>an identifier is blocked for a caller address after {@code maxPerIdentifier} failures FROM THAT ADDRESS;</li>
 *   <li>an identifier is blocked for everyone after {@code maxPerIdentifierAnyAddress} failures from any address
 *       (distributed guessing), which is deliberately higher so a stranger cannot lock the real owner out with a
 *       handful of requests from another address;</li>
 *   <li>a client address is blocked after {@code maxPerIp} failures.</li>
 * </ul>
 * Pure data and rule; counting is the repository's job. This is not the same concept as the per-credential lockout
 * after N wrong temporary secrets, which is a persistent counter with no window.
 */
public record ThrottlePolicy(int maxPerIdentifier, int maxPerIp, int windowMinutes, int maxPerIdentifierAnyAddress) {

    /**
     * The budget that this very failure just used up, or null. Every recorded failure raises each count by one, so a
     * count that EQUALS its limit happened exactly once per window: that is the moment worth auditing (not each
     * refused request afterwards, which an attacker could use to flood the audit log).
     */
    public String limitJustReached(long failuresForIdentifierFromAddress, long failuresForIdentifierAnyAddress, long failuresForIp) {
        if (failuresForIdentifierFromAddress == maxPerIdentifier) return "IDENTIFIER_FROM_ADDRESS";
        if (failuresForIdentifierAnyAddress == maxPerIdentifierAnyAddress) return "IDENTIFIER_ANY_ADDRESS";
        if (failuresForIp == maxPerIp) return "ADDRESS";
        return null;
    }

    /** True when any budget is used up. */
    public boolean blocks(long failuresForIdentifierFromAddress, long failuresForIdentifierAnyAddress, long failuresForIp) {
        return failuresForIdentifierFromAddress >= maxPerIdentifier
            || failuresForIdentifierAnyAddress >= maxPerIdentifierAnyAddress
            || failuresForIp >= maxPerIp;
    }
}
