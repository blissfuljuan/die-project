package com.blissfuljuan.dieapi.config;

import com.blissfuljuan.dieapi.common.util.ProblemDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gives every request a correlation id before anything else runs (including Spring Security): it is put in the
 * logging context, returned as the X-Request-Id header and reported as {@code traceId} in every error body, so a
 * user's error message can be matched to the server log. A well-formed id sent by a trusted caller is kept;
 * anything else (too long, odd characters, header injection attempts) is replaced.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(ProblemDetails.REQUEST_ID_HEADER);
        String id = incoming != null && VALID.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
        MDC.put(ProblemDetails.REQUEST_ID_KEY, id);
        request.setAttribute(ProblemDetails.REQUEST_ID_KEY, id);   // error dispatches run without the MDC entry
        response.setHeader(ProblemDetails.REQUEST_ID_HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(ProblemDetails.REQUEST_ID_KEY);
        }
    }
}
