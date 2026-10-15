package com.blissfuljuan.die.auth.service;

import java.util.Collection;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Length-based policy (NIST 800-63B style): no composition rules, but no reuse of known identifiers. */
@Component
public class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 128;

    /** @param forbidden values the password must not equal (identifier, email, temporary credential...) */
    public void validate(String password, Collection<String> forbidden) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw bad("Password must be at least " + MIN_LENGTH + " characters");
        }
        if (password.length() > MAX_LENGTH) {
            throw bad("Password must be at most " + MAX_LENGTH + " characters");
        }
        if (password.isBlank()) {
            throw bad("Password cannot be blank");
        }
        for (String f : forbidden) {
            if (f != null && !f.isBlank() && f.equalsIgnoreCase(password)) {
                throw bad("Password is too easy to guess");
            }
        }
    }

    private static ResponseStatusException bad(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
