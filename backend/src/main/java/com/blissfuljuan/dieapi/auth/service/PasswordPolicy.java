package com.blissfuljuan.dieapi.auth.service;

import org.springframework.stereotype.Component;
import com.blissfuljuan.dieapi.common.exception.ApiException;
import com.blissfuljuan.dieapi.common.exception.ErrorCode;

import java.util.Collection;

/** Length-based policy (NIST 800-63B style): no composition rules, but no reuse of known identifiers, and no known-weak password ({@link CommonPasswords}). */
@Component
public class PasswordPolicy {

    private final CommonPasswords common;

    public PasswordPolicy(CommonPasswords common) {
        this.common = common;
    }

    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 128;

    /** @param forbidden values the password must not equal (identifier, email, temporary credential...) */
    public void validate(String password, Collection<String> forbidden) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw bad(ErrorCode.PASSWORD_TOO_SHORT, "Password must be at least " + MIN_LENGTH + " characters");
        }
        if (password.length() > MAX_LENGTH) {
            throw bad(ErrorCode.PASSWORD_TOO_LONG, "Password must be at most " + MAX_LENGTH + " characters");
        }
        if (password.isBlank()) {
            throw bad(ErrorCode.PASSWORD_BLANK, "Password cannot be blank");
        }
        if (common.isWeak(password)) {
            throw bad(ErrorCode.PASSWORD_TOO_EASY, "Password is too easy to guess");
        }
        for (String f : forbidden) {
            if (f != null && !f.isBlank() && f.equalsIgnoreCase(password)) {
                throw bad(ErrorCode.PASSWORD_TOO_EASY, "Password is too easy to guess");
            }
        }
    }

    private static ApiException bad(ErrorCode code, String reason) {
        return ApiException.badRequest(code, reason);
    }
}
