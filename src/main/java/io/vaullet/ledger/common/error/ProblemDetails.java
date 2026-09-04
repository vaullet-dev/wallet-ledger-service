package io.vaullet.ledger.common.error;

import io.micrometer.tracing.Tracer;
import java.net.URI;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ProblemDetail;
import org.springframework.web.context.request.WebRequest;

/**
 * Builds the one error body this service returns — RFC 9457 {@code problem+json} in the shape
 * ADR-011, §7 fixes.
 *
 * <p>Extracted from {@link ApiExceptionHandler} because there is a second {@code @RestControllerAdvice}
 * in the API layer bridging the ledger's legacy exceptions, and two advices producing
 * <em>almost</em> the same body is how a client ends up branching on a {@code code} that is present
 * on some errors and absent on others. One function, one shape, both callers.
 *
 * <p>{@code code} and {@code trace_id} are written in snake_case literally: a {@code ProblemDetail}
 * keeps extension properties in a plain map, and Jackson's property-naming strategy does not rewrite
 * map keys the way it rewrites record components.
 */
public final class ProblemDetails {

    private ProblemDetails() {}

    /**
     * @param tracer optional; tracing is not auto-configured in every test slice, and an error
     *     response missing its correlation id is far better than an error handler that throws
     */
    public static ProblemDetail create(
            ErrorType errorType, String detail, WebRequest request, ObjectProvider<Tracer> tracer) {

        var problem = ProblemDetail.forStatusAndDetail(errorType.status(), detail);
        problem.setType(errorType.type());
        problem.setTitle(errorType.title());
        problem.setInstance(instanceOf(request));
        problem.setProperty("code", errorType.code());
        problem.setProperty("timestamp", Instant.now());

        var traceId = currentTraceId(tracer);
        if (traceId != null) {
            problem.setProperty("trace_id", traceId);
        }
        return problem;
    }

    private static @Nullable URI instanceOf(WebRequest request) {
        var description = request.getDescription(false);
        return description.startsWith("uri=") ? URI.create(description.substring(4)) : null;
    }

    private static @Nullable String currentTraceId(ObjectProvider<Tracer> tracer) {
        var current = tracer.getIfAvailable();
        if (current == null || current.currentSpan() == null) {
            return null;
        }
        return current.currentSpan().context().traceId();
    }
}
