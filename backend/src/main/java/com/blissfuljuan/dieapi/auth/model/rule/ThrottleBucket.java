package com.blissfuljuan.dieapi.auth.model.rule;

/**
 * Separates throttle budgets that must not consume each other. Password-reset requests live in their own
 * bucket (identifiers prefixed "pwreset:") so anonymous reset requests can never use up the per-address
 * login budget. Every other identifier (login email, "act:" activation, "pwchange:" change) is LOGIN.
 */
public enum ThrottleBucket {
    LOGIN,
    PASSWORD_RESET;

    public static final String RESET_PREFIX = "pwreset:";

    /** Identifier used to count a password-reset request for this (already normalised) email. */
    public static String resetKey(String normalisedEmail) {
        return RESET_PREFIX + normalisedEmail;
    }
}
