package com.blissfuljuan.die.common.controller;

import com.blissfuljuan.die.common.util.ProblemDetails;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fallback for errors raised by the servlet container itself (malformed URLs, oversized headers, ...), which are
 * re-dispatched to /error. Replaces Spring Boot's default JSON so that even these failures use the common format.
 * Spring MVC exceptions never get here: GlobalExceptionHandler answers them.
 */
@Hidden
@RestController
public class ErrorPageController implements ErrorController {

    @RequestMapping("/error")
    public ResponseEntity<ProblemDetail> error(HttpServletRequest request) {
        Object raw = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int value = raw instanceof Integer i ? i : 500;
        HttpStatus status = HttpStatus.resolve(value) == null ? HttpStatus.INTERNAL_SERVER_ERROR : HttpStatus.resolve(value);

        ProblemDetail problem = ProblemDetails.of(status, ProblemDetails.codeFor(status), detailFor(status));
        Object path = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        if (path instanceof String p) {
            problem.setInstance(URI.create(p.replaceAll("[^A-Za-z0-9/_.~%-]", "_")));
        }
        if (!problem.getProperties().containsKey("traceId") && request.getAttribute(ProblemDetails.REQUEST_ID_KEY) instanceof String id) {
            problem.setProperty("traceId", id);
        }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    private static String detailFor(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "The request could not be understood. Check the URL and the request format.";
            case UNAUTHORIZED -> "Authentication is required. Sign in and try again.";
            case FORBIDDEN -> "You do not have permission to perform this action.";
            case NOT_FOUND -> "The requested resource was not found.";
            case METHOD_NOT_ALLOWED -> "This HTTP method is not supported for this resource.";
            case PAYLOAD_TOO_LARGE -> "The request is too large.";
            case URI_TOO_LONG -> "The URL is too long.";
            default -> status.is5xxServerError()
                ? "Something went wrong on our side. Try again later."
                : "The request could not be processed.";
        };
    }
}
