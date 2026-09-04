package io.vaullet.ledger.reservation.api;

import io.micrometer.tracing.Tracer;
import io.vaullet.ledger.common.error.ErrorType;
import io.vaullet.ledger.common.error.ProblemDetails;
import io.vaullet.ledger.reservation.service.LedgerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

/**
 * Temporary translation for the exceptions {@code LedgerService} still throws.
 *
 * <p><b>This class is scaffolding and is meant to be deleted.</b> {@code LedgerService} predates the
 * {@code common/error} layer: it throws three exception types nested inside itself, and surfaces an
 * unknown reservation or account as Spring's {@code EmptyResultDataAccessException}. None of those
 * carry an {@link ErrorType}, so without this bridge {@code INSUFFICIENT_FUNDS} — the single most
 * important response this service gives — would reach callers as a 500.
 *
 * <p>The end state is the one the template describes: the service throws
 * {@code InsufficientFundsException}, {@code HoldTtlTooLongException} and
 * {@code ResourceNotFoundException} from {@code common/error}, {@code ApiExceptionHandler}'s single
 * {@code ApplicationException} handler covers all of them, the {@code @PreAuthorize} rules move
 * from the controllers down onto the service methods they protect, and this file goes away. The
 * mapping below is deliberately mechanical so that change is a deletion rather than a rewrite.
 *
 * <p>{@code @Order(HIGHEST_PRECEDENCE)} matters: {@code ApiExceptionHandler} has a catch-all
 * {@code @ExceptionHandler(Exception.class)}, and Spring consults advices in order. Without this,
 * the catch-all would win and every ledger error would be a 500 — the exact bug this class exists
 * to prevent.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
class LedgerErrorBridge {

    private static final Logger log = LoggerFactory.getLogger(LedgerErrorBridge.class);

    private final ObjectProvider<Tracer> tracer;

    LedgerErrorBridge(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    /**
     * ADR-004's business signal. Debug, not warn: a permanently non-zero rate is the design working,
     * and logging it at warn would train everyone to ignore warnings.
     */
    @ExceptionHandler(LedgerService.InsufficientFunds.class)
    ProblemDetail handleInsufficientFunds(LedgerService.InsufficientFunds ex, WebRequest request) {
        log.debug("Reservation refused: {}", ex.getMessage());
        return ProblemDetails.create(ErrorType.INSUFFICIENT_FUNDS, ex.getMessage(), request, tracer);
    }

    @ExceptionHandler(LedgerService.HoldTtlTooLong.class)
    ProblemDetail handleHoldTtlTooLong(LedgerService.HoldTtlTooLong ex, WebRequest request) {
        return ProblemDetails.create(ErrorType.HOLD_TTL_TOO_LONG, ex.getMessage(), request, tracer);
    }

    @ExceptionHandler(LedgerService.AccountNotFound.class)
    ProblemDetail handleAccountNotFound(LedgerService.AccountNotFound ex, WebRequest request) {
        return ProblemDetails.create(ErrorType.RESOURCE_NOT_FOUND, "Account was not found", request, tracer);
    }

    /**
     * {@code release}, {@code settle} and {@code balance} report an unknown id this way.
     *
     * <p>The detail is generic on purpose. The exception's own message names the row count the query
     * expected, which tells a caller nothing and tells an attacker probing for valid account ids
     * slightly more than nothing.
     */
    @ExceptionHandler(EmptyResultDataAccessException.class)
    ProblemDetail handleMissingRow(EmptyResultDataAccessException ex, WebRequest request) {
        log.debug("Addressed a row that does not exist", ex);
        return ProblemDetails.create(
                ErrorType.RESOURCE_NOT_FOUND, "The requested resource was not found", request, tracer);
    }
}
