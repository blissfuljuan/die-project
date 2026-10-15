package com.blissfuljuan.die.common.controller;

import com.blissfuljuan.die.common.exception.ApiException;
import com.blissfuljuan.die.common.util.ProblemDetails;
import com.blissfuljuan.die.common.view.FieldErrorView;
import com.fasterxml.jackson.databind.JsonMappingException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * The single place where failures become HTTP responses. Every error leaves the API in the same RFC 9457 shape
 * (see {@link ProblemDetails}): a status, a person-readable {@code detail}, a stable {@code code}, a timestamp and
 * a {@code traceId} that is also the {@code X-Request-Id} header and appears in the server log.
 *
 * Rules: say what went wrong and what to do about it in plain language; never echo secrets or rejected values;
 * never leak internals (stack traces, SQL, class names) — unexpected failures get a generic message plus the
 * traceId so support can find the log entry.
 *
 * Failures raised before a controller is reached (401/403 from the security filters) are produced by
 * JsonAuthenticationEntryPoint / JsonAccessDeniedHandler in the same format.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ================= deliberate failures and Spring MVC's own exceptions =================

    /** Adds code/timestamp/traceId to everything the base class produces, including ApiException and ResponseStatusException. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        Object payload = body;
        if (payload == null && ex instanceof ErrorResponse errorResponse) {
            payload = errorResponse.updateAndGetBody(getMessageSource(), LocaleContextHolder.getLocale());
        }
        if (payload instanceof ProblemDetail problem) {
            if (problem.getTitle() == null) {
                HttpStatus http = HttpStatus.resolve(statusCode.value());
                if (http != null) problem.setTitle(http.getReasonPhrase());
            }
            String code = ex instanceof ApiException api ? api.getCode() : ProblemDetails.codeFor(statusCode);
            ProblemDetails.enrich(problem, code);
        }
        return super.handleExceptionInternal(ex, payload, headers, statusCode, request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = problem(status, "VALIDATION_FAILED",
            "Some fields are missing or invalid. Fix the fields listed in 'errors' and try again.");
        problem.setProperty("errors", fieldErrors(ex.getBindingResult()));
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers, HttpStatusCode status,
                                                                            WebRequest request) {
        List<FieldErrorView> errors = new ArrayList<>();
        ex.getAllValidationResults().forEach(result -> {
            String name = result.getMethodParameter().getParameterName();
            result.getResolvableErrors().forEach(error ->
                errors.add(new FieldErrorView(name == null ? "parameter" : name, error.getDefaultMessage())));
        });
        ProblemDetail problem = problem(status, "VALIDATION_FAILED",
            "Some parameters are missing or invalid. Fix the items listed in 'errors' and try again.");
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        String detail = "The request body is missing or is not valid JSON.";
        if (ex.getCause() instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()) {
            String field = mapping.getPath().stream().map(JsonMappingException.Reference::getFieldName)
                .filter(name -> name != null && !name.isBlank()).collect(Collectors.joining("."));
            if (!field.isBlank()) {
                detail = "The value of field '" + field + "' has the wrong type or format.";
            }
        }
        return handleExceptionInternal(ex, problem(status, "MALFORMED_REQUEST", detail), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(MissingServletRequestParameterException ex,
                                                                          HttpHeaders headers, HttpStatusCode status,
                                                                          WebRequest request) {
        ProblemDetail problem = problem(status, "MISSING_PARAMETER",
            "The required parameter '" + ex.getParameterName() + "' is missing.");
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestPart(MissingServletRequestPartException ex,
                                                                     HttpHeaders headers, HttpStatusCode status,
                                                                     WebRequest request) {
        ProblemDetail problem = problem(status, "MISSING_PART",
            "The multipart form field '" + ex.getRequestPartName() + "' is required (attach the file).");
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
                                                        HttpStatusCode status, WebRequest request) {
        String detail = "A parameter has an invalid value.";
        if (ex instanceof MethodArgumentTypeMismatchException mismatch) {
            detail = "Parameter '" + mismatch.getName() + "' must be " + expected(mismatch.getRequiredType()) + ".";
        }
        return handleExceptionInternal(ex, problem(status, "INVALID_PARAMETER", detail), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                                         HttpHeaders headers, HttpStatusCode status,
                                                                         WebRequest request) {
        String allowed = ex.getSupportedHttpMethods() == null ? "" : ex.getSupportedHttpMethods().stream()
            .map(Object::toString).collect(Collectors.joining(", "));
        String detail = "The " + ex.getMethod() + " method is not supported for this endpoint."
            + (allowed.isEmpty() ? "" : " Use one of: " + allowed + ".");
        return handleExceptionInternal(ex, problem(status, "METHOD_NOT_ALLOWED", detail), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex,
                                                                     HttpHeaders headers, HttpStatusCode status,
                                                                     WebRequest request) {
        String supported = ex.getSupportedMediaTypes().stream().map(MediaType::toString).collect(Collectors.joining(", "));
        String sent = ex.getContentType() == null ? "none" : ex.getContentType().toString();
        String detail = "The content type '" + sent + "' is not supported here."
            + (supported.isEmpty() ? "" : " Send one of: " + supported + ".");
        return handleExceptionInternal(ex, problem(status, "UNSUPPORTED_MEDIA_TYPE", detail), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotAcceptable(HttpMediaTypeNotAcceptableException ex,
                                                                      HttpHeaders headers, HttpStatusCode status,
                                                                      WebRequest request) {
        ProblemDetail problem = problem(status, "NOT_ACCEPTABLE",
            "This endpoint cannot produce any of the content types you asked for (Accept header). Ask for application/json.");
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException ex, HttpHeaders headers,
                                                                    HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = problem(status, "ENDPOINT_NOT_FOUND",
            "There is no endpoint at '" + ex.getHttpMethod() + " /" + ex.getResourcePath() + "'. Check the URL and the HTTP method.");
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex,
                                                                          HttpHeaders headers, HttpStatusCode status,
                                                                          WebRequest request) {
        long max = ex.getMaxUploadSize();
        String limit = max > 0 ? " The limit is " + Math.max(1, max / (1024 * 1024)) + " MB." : "";
        return handleExceptionInternal(ex, problem(status, "PAYLOAD_TOO_LARGE", "The uploaded file is too large." + limit),
            headers, status, request);
    }

    // ================= everything else =================

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        ProblemDetail problem = ProblemDetails.of(HttpStatus.FORBIDDEN, "ACCESS_DENIED",
            "You do not have permission to perform this action.");
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.FORBIDDEN, request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Object> handleAuthentication(AuthenticationException ex, WebRequest request) {
        ProblemDetail problem = ProblemDetails.of(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED",
            "Authentication is required. Sign in and try again.");
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.UNAUTHORIZED, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex, WebRequest request) {
        List<FieldErrorView> errors = new ArrayList<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            String path = violation.getPropertyPath().toString();
            errors.add(new FieldErrorView(path.substring(path.lastIndexOf('.') + 1), violation.getMessage()));
        }
        ProblemDetail problem = ProblemDetails.of(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
            "Some values are invalid. Fix the items listed in 'errors' and try again.");
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.BAD_REQUEST, request);
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Object> handleDuplicate(DuplicateKeyException ex, WebRequest request) {
        log.debug("Unique constraint violated", ex);
        ProblemDetail problem = ProblemDetails.of(HttpStatus.CONFLICT, "ALREADY_EXISTS",
            "A record with the same unique value (for example an email address or Student ID) already exists.");
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.CONFLICT, request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> handleIntegrity(DataIntegrityViolationException ex, WebRequest request) {
        log.warn("Data integrity violation (traceId={})", MDC.get(ProblemDetails.REQUEST_ID_KEY), ex);
        ProblemDetail problem = ProblemDetails.of(HttpStatus.CONFLICT, "DATA_CONFLICT",
            "The request conflicts with existing data and could not be saved. Reload and try again.");
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.CONFLICT, request);
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    public ResponseEntity<Object> handleDatabaseUnavailable(Exception ex, WebRequest request) {
        log.error("Database unavailable (traceId={})", MDC.get(ProblemDetails.REQUEST_ID_KEY), ex);
        ProblemDetail problem = ProblemDetails.of(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
            "The service is temporarily unavailable. Try again in a moment.");
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.SERVICE_UNAVAILABLE, request);
    }

    /** Last resort. The caller gets no internals, only a generic message and the reference to quote. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        String traceId = MDC.get(ProblemDetails.REQUEST_ID_KEY);
        String where = request instanceof ServletWebRequest servlet
            ? servlet.getHttpMethod() + " " + servlet.getRequest().getRequestURI() : "request";
        log.error("Unhandled exception (traceId={}) for {}", traceId, where, ex);
        String reference = traceId == null ? "" : " Quote reference " + traceId + " if you contact support.";
        ProblemDetail problem = ProblemDetails.of(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
            "Something went wrong on our side." + reference);
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    // ================= helpers =================

    private static ProblemDetail problem(HttpStatusCode status, String code, String detail) {
        HttpStatus http = HttpStatus.resolve(status.value());
        return ProblemDetails.of(http == null ? HttpStatus.BAD_REQUEST : http, code, detail);
    }

    private static List<FieldErrorView> fieldErrors(BindingResult result) {
        List<FieldErrorView> errors = new ArrayList<>();
        for (FieldError error : result.getFieldErrors()) {
            errors.add(new FieldErrorView(error.getField(), error.getDefaultMessage()));
        }
        result.getGlobalErrors().forEach(error -> errors.add(new FieldErrorView(error.getObjectName(), error.getDefaultMessage())));
        return errors;
    }

    private static String expected(Class<?> type) {
        if (type == null) return "a valid value";
        if (type.getSimpleName().equals("UUID")) return "a valid UUID";
        if (Number.class.isAssignableFrom(type) || type.isPrimitive() && !type.equals(boolean.class)) return "a number";
        if (type.equals(Boolean.class) || type.equals(boolean.class)) return "true or false";
        if (type.isEnum()) {
            return "one of " + java.util.Arrays.stream(type.getEnumConstants()).map(Object::toString)
                .collect(Collectors.joining(", "));
        }
        return "a valid " + type.getSimpleName();
    }
}
