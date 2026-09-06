package io.vaullet.ledger.common.error;

import io.micrometer.tracing.Tracer;
import io.vaullet.common.error.ErrorType;
import io.vaullet.common.security.SecurityApiExceptionHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The shared exception handler, with one thing renamed.
 *
 * <p>{@code backend-common} answers a contended row with {@code RESOURCE_BUSY}. On the ledger the
 * contended row is always an account, and {@code ACCOUNT_BUSY} is already published in this
 * service's API contract and README — so renaming it would be a breaking change under ADR-011, §4
 * for the sake of tidiness. Overriding one method is the cheaper answer, and it is the extension
 * point {@code ApiExceptionHandler} documents.
 *
 * <p>Everything else — the single {@code ApplicationException} handler, the validation bodies, the
 * opaque 500, the {@code AccessDeniedException} mapping this inherits from
 * {@link SecurityApiExceptionHandler} — comes from the library. Declaring this bean is also what
 * makes both auto-configurations back off, so there is exactly one advice producing error bodies.
 */
@RestControllerAdvice
class LedgerApiExceptionHandler extends SecurityApiExceptionHandler {

    LedgerApiExceptionHandler(ObjectProvider<Tracer> tracer) {
        super(tracer);
    }

    @Override
    protected ErrorType lockContentionErrorType() {
        return LedgerErrorType.ACCOUNT_BUSY;
    }
}
