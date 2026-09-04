package io.vaullet.ledger.common.error;

import io.micrometer.tracing.Tracer;
import jakarta.validation.ConstraintViolationException;
import java.util.Comparator;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Single place where exceptions become HTTP responses, in RFC 9457 {@code problem+json}.
 *
 * <p>Design notes worth copying into your own services:
 *
 * <ul>
 *   <li><b>Extend {@link ResponseEntityExceptionHandler}.</b> It already handles the ~15 Spring MVC
 *       exceptions (unreadable body, unsupported media type, missing header, …) as
 *       {@code ProblemDetail}. Writing an {@code @ExceptionHandler} for each re-invents a wheel that
 *       ships in the box.
 *   <li><b>One handler per <em>domain</em> failure, not per call site.</b> Controllers contain no
 *       try/catch and no {@code ResponseEntity.status(...)} plumbing.
 *   <li><b>Never leak internals.</b> Unexpected exceptions are logged with the stack trace and the
 *       correlation id; the client gets a generic body plus that id. Echoing {@code getMessage()} is
 *       how table names and SQL end up in a bug tracker — a particularly bad outcome here, where the
 *       statements are the ledger's design.
 *   <li><b>The body shape is ADR-011, §7.</b> Alongside RFC 9457's {@code type}/{@code title}/
 *       {@code status}/{@code detail}/{@code instance} it carries {@code code} (the stable contract
 *       a client branches on) and {@code trace_id} (ADR-007's {@code correlation_id}). Both keys are
 *       written in snake_case literally: {@code ProblemDetail}'s extension properties are a plain
 *       map, and Jackson's naming strategy does not rewrite map keys.
 * </ul>
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Optional: tracing is not auto-configured in every test slice. */
    private final ObjectProvider<Tracer> tracer;

    ApiExceptionHandler(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    /**
     * Every failure the ledger raises on purpose, including {@code INSUFFICIENT_FUNDS}.
     *
     * <p>Logged at debug, not warn: ADR-004 treats a refused reservation as a business signal with a
     * permanently non-zero rate. Logging it at warn would train everyone to ignore warnings.
     */
    @ExceptionHandler(ApplicationException.class)
    ProblemDetail handleApplicationException(ApplicationException ex, WebRequest request) {
        log.debug("Handled application error [{}]: {}", ex.errorType().code(), ex.getMessage());
        return problem(ex.errorType(), ex.getMessage(), request);
    }

    /**
     * The account row lock could not be taken within {@code statement_timeout}.
     *
     * <p>A hot account, not a broken request: the same call a moment later will very likely work, so
     * it gets a 503 and a {@code Retry-After} rather than a 500. Returning {@code ResponseEntity}
     * instead of a bare {@code ProblemDetail} is what makes room for that header.
     */
    @ExceptionHandler({QueryTimeoutException.class, CannotAcquireLockException.class})
    ResponseEntity<ProblemDetail> handleLockContention(Exception ex, WebRequest request) {
        log.warn("Lock contention or statement timeout on the money path", ex);
        var problem = problem(
                ErrorType.ACCOUNT_BUSY, "The account is busy settling another operation. Retry shortly.", request);
        return ResponseEntity.status(problem.getStatus())
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(problem);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        // Deliberately vague: telling a caller *why* they were denied is an information leak.
        return problem(ErrorType.ACCESS_DENIED, "You do not have permission to perform this action", request);
    }

    /** {@code @Validated} on method parameters, as opposed to {@code @Valid} on a body. */
    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail handleConstraintViolation(ConstraintViolationException ex, WebRequest request) {
        var problem = problem(ErrorType.VALIDATION_FAILED, "One or more parameters are invalid", request);
        problem.setProperty(
                "errors",
                ex.getConstraintViolations().stream()
                        .map(violation -> new FieldError(
                                violation.getPropertyPath().toString(), violation.getMessage()))
                        .sorted(Comparator.comparing(FieldError::field))
                        .toList());
        return problem;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception", ex);
        return problem(ErrorType.INTERNAL_ERROR, "An unexpected error occurred", request);
    }

    /**
     * Enriches Spring's own body-validation problem with a structured {@code errors} array.
     *
     * <p>Clients need field-level detail to highlight the offending input; a flat sentence forces
     * them to parse prose.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        var problem = problem(ErrorType.VALIDATION_FAILED, "One or more fields are invalid", request);
        problem.setProperty(
                "errors",
                ex.getBindingResult().getFieldErrors().stream()
                        .map(error -> new FieldError(error.getField(), error.getDefaultMessage()))
                        .sorted(Comparator.comparing(FieldError::field))
                        .toList());
        return ResponseEntity.status(problem.getStatus()).headers(headers).body(problem);
    }

    private ProblemDetail problem(ErrorType errorType, String detail, WebRequest request) {
        return ProblemDetails.create(errorType, detail, request, tracer);
    }

    /** Field-level validation failure, serialised into the problem's {@code errors} array. */
    record FieldError(String field, @Nullable String message) {}
}
