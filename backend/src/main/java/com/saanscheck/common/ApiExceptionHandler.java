package com.saanscheck.common;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps exceptions to RFC 7807 problem responses with clear messages (spec: "fail fast with clear errors").
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} so Spring MVC's own exceptions (malformed JSON, 405, 415,
 * missing or mistyped parameters, unknown resource) are answered directly with their real status. Previously they
 * were forwarded to {@code /error}, which Spring Security rejects for anonymous callers, so a client mistake on a
 * public endpoint was reported as 401 Unauthorized. Unexpected exceptions become a generic 500 (logged in full,
 * never echoed to the client).
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail conflict(ConflictException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(RateLimitedException.class)
    ProblemDetail rateLimited(RateLimitedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    @ExceptionHandler(BadCredentialsException.class)
    ProblemDetail badCredentials(BadCredentialsException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid email or password");
    }

    @ExceptionHandler(com.saanscheck.security.LoginAttemptLimiter.TooManyAttemptsException.class)
    ProblemDetail tooManyAttempts(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "Too many failed login attempts; try again later");
    }

    @ExceptionHandler({IllegalArgumentException.class, ConstraintViolationException.class})
    ProblemDetail badRequest(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Invalid request");
        return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail));
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException e,
                                                                            HttpHeaders headers, HttpStatusCode status,
                                                                            WebRequest request) {
        return ResponseEntity.badRequest()
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request parameters"));
    }

    /** "lat=abc": name the parameter and value, not Java types. */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException e, HttpHeaders headers,
                                                        HttpStatusCode status, WebRequest request) {
        String name = e.getPropertyName() != null ? e.getPropertyName() : "parameter";
        return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Invalid value for '" + name + "': " + e.getValue()));
    }

    @ExceptionHandler({CannotGetJdbcConnectionException.class, DataAccessResourceFailureException.class})
    ProblemDetail databaseDown(RuntimeException e) {
        log.error("Database unavailable: {}", e.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Database unavailable");
    }

    /** Anything else is a server bug: log it, answer 500 without internal details. */
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) throws Exception {
        if (e instanceof AccessDeniedException || e instanceof AuthenticationException) {
            throw e;   // let Spring Security translate these to 401/403
        }
        log.error("Unhandled exception", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    }
}
