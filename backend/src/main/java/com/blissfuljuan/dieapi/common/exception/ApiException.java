package com.blissfuljuan.dieapi.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A deliberate, client-facing failure: an HTTP status, a stable machine-readable {@link ErrorCode} (clients switch on it)
 * and a message safe to show to a person. Extends ResponseStatusException so that code that still throws the
 * plain Spring exception is handled by the same global handler (its code is derived from the status).
 */
public class ApiException extends ResponseStatusException {

    private final String code;

    public ApiException(HttpStatus status, ErrorCode code, String message) {
        this(status, code.value(), message);
    }

    /**
     * Escape hatch for subclasses only (protected on purpose): production code must name an {@link ErrorCode}. It
     * exists so that test support can throw a code that is not part of the API, to prove the handler passes any
     * ApiException code through unchanged.
     */
    protected ApiException(HttpStatus status, String rawCode, String message) {
        super(status, message);
        this.code = rawCode;
    }

    /** The stable code as written to the response body. */
    public String getCode() {
        return code;
    }

    public static ApiException badRequest(ErrorCode code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException unauthorized(ErrorCode code, String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code, message);
    }

    public static ApiException forbidden(ErrorCode code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message);
    }

    public static ApiException notFound(ErrorCode code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static ApiException conflict(ErrorCode code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException tooManyRequests(ErrorCode code, String message) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, code, message);
    }

    public static ApiException unavailable(ErrorCode code, String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, message);
    }
}
