package com.blissfuljuan.dieapi.config;

import com.blissfuljuan.dieapi.common.exception.ErrorCode;
import com.blissfuljuan.dieapi.common.util.ProblemDetails;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** 401 for requests that need a signed-in user and have none, in the common error format (not an empty body). */
@Component
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper mapper;

    public JsonAuthenticationEntryPoint(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        ProblemDetails.write(request, response, mapper, ProblemDetails.of(HttpStatus.UNAUTHORIZED, ErrorCode.AUTHENTICATION_REQUIRED,
            "Authentication is required to use this endpoint. Sign in (POST /api/v1/auth/login) and try again."));
    }
}
