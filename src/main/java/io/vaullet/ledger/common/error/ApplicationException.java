package io.vaullet.ledger.common.error;

import java.io.Serial;

/**
 * Base class for errors this service raises deliberately, each carrying the {@link ErrorType} that
 * determines its HTTP representation.
 *
 * <p>The point is that the <em>service</em> layer throws in domain terms and stays free of
 * {@code ResponseEntity}, {@code HttpStatus} and servlet types. Translation to HTTP happens once,
 * in {@link ApiExceptionHandler}. That is what keeps {@code LedgerService} callable from a Kafka
 * listener settling a transaction (ADR-004's revised flow, step 6) or from a sweeper expiring
 * stale holds, without dragging the web stack along.
 */
public abstract class ApplicationException extends RuntimeException {

    // Throwable is Serializable, so every subclass inherits the obligation. Pinning the value keeps
    // -Xlint quiet and stops a harmless field addition from breaking deserialisation.
    @Serial
    private static final long serialVersionUID = 1L;

    private final ErrorType errorType;

    protected ApplicationException(ErrorType errorType, String message) {
        super(message);
        this.errorType = errorType;
    }

    protected ApplicationException(ErrorType errorType, String message, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
    }

    public ErrorType errorType() {
        return errorType;
    }
}
