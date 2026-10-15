package com.blissfuljuan.die.config;

import com.blissfuljuan.die.common.util.ProblemDetails;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;

/**
 * 403 raised by the security filters, in the common error format. A CSRF failure gets its own code and tells the
 * client exactly how to fix it; everything else is a plain "not allowed".
 */
@Component
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper mapper;

    public JsonAccessDeniedHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        ProblemDetail problem = ex instanceof CsrfException
            ? ProblemDetails.of(HttpStatus.FORBIDDEN, "CSRF_TOKEN_INVALID",
                "The CSRF token is missing or invalid. Fetch one with GET /api/v1/auth/csrf and send it in the "
                    + "X-XSRF-TOKEN header (browsers also keep it in the XSRF-TOKEN cookie).")
            : ProblemDetails.of(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "You do not have permission to perform this action.");
        ProblemDetails.write(request, response, mapper, problem);
    }
}
