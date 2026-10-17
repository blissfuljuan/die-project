package com.blissfuljuan.dieapi.common.util;

import com.blissfuljuan.dieapi.common.exception.ErrorCode;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;

/**
 * The one error format of the API (RFC 9457 problem details) and the helpers that build it, used by the global
 * exception handler (errors raised inside controllers) and by the security filters (401/403 raised before a
 * controller is reached), so that clients see the same shape everywhere:
 * <pre>
 * { "type": "about:blank", "title": "Not Found", "status": 404,
 *   "detail": "Class not found",            // a sentence a person can read
 *   "instance": "/api/v1/classes/...",      // the request path
 *   "code": "CLASS_NOT_FOUND",              // stable, machine-readable: clients branch on this
 *   "timestamp": "2026-10-15T14:05:18Z",
 *   "traceId": "…",                         // also the X-Request-Id response header and in the server log
 *   "errors": [ { "field": "email", "message": "must not be blank" } ]   // validation failures only
 * }
 * </pre>
 */
public final class ProblemDetails {

    public static final String REQUEST_ID_KEY = "requestId";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    private ProblemDetails() {}

    public static ProblemDetail of(HttpStatus status, ErrorCode code, String detail) {
        return of(status, code.value(), detail);
    }

    /** For the status-derived fallback code only ({@link #codeFor}); everything that names a code uses {@link ErrorCode}. */
    public static ProblemDetail of(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        enrich(problem, code);
        return problem;
    }

    /** Adds the common members (code, timestamp, traceId) to a problem detail, keeping any it already has. */
    public static ProblemDetail enrich(ProblemDetail problem, String code) {
        if (problem.getProperties() == null || !problem.getProperties().containsKey("code")) {
            problem.setProperty("code", code);
        }
        if (problem.getProperties() == null || !problem.getProperties().containsKey("timestamp")) {
            problem.setProperty("timestamp", Instant.now().toString());
        }
        String traceId = MDC.get(REQUEST_ID_KEY);
        if (traceId != null && (problem.getProperties() == null || !problem.getProperties().containsKey("traceId"))) {
            problem.setProperty("traceId", traceId);
        }
        return problem;
    }

    /** Stable error code for a status when the failure did not name one. */
    public static String codeFor(HttpStatusCode status) {
        HttpStatus http = HttpStatus.resolve(status.value());
        if (http == null) return status.is5xxServerError() ? "INTERNAL_ERROR" : "BAD_REQUEST";
        return switch (http) {
            case INTERNAL_SERVER_ERROR -> "INTERNAL_ERROR";
            case UNPROCESSABLE_ENTITY -> "UNPROCESSABLE";
            default -> http.name();
        };
    }

    /** Writes a problem detail as the whole response (for filters and handlers that run outside Spring MVC). */
    public static void write(HttpServletRequest request, HttpServletResponse response, ObjectMapper mapper,
                             ProblemDetail problem) throws IOException {
        if (problem.getInstance() == null) {
            problem.setInstance(URI.create(request.getRequestURI()));
        }
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getOutputStream(), problem);
    }
}
