package io.vaullet.ledger.common.error;

import io.vaullet.common.error.CommonErrorType;
import io.vaullet.common.error.ErrorType;
import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * The error codes that belong to the ledger, alongside the platform ones in
 * {@link CommonErrorType}.
 *
 * <p>Everything here is money vocabulary. That is the test {@code backend-common-core} applies for
 * membership in the shared catalogue — "would a service that knows nothing about money still raise
 * it" — and these four fail it, so they live in the service that owns them. ADR-011, §7 names
 * {@code INSUFFICIENT_FUNDS} and {@code CURRENCY_MISMATCH} as platform codes, and they will
 * <em>become</em> shared the day a second service raises them; a code used by exactly one service
 * is that service's vocabulary however platform-shaped it sounds.
 *
 * <p>Adding an entry is additive and safe. Changing what an existing entry <em>means</em> is a
 * breaking change for every caller that branches on it, so it needs a new major API version
 * (ADR-011, §4).
 */
public enum LedgerErrorType implements ErrorType {

    /**
     * ADR-004's business signal, and the reason this service exists. A 409 rather than a 422: the
     * request is well-formed and would be valid against a different balance.
     */
    INSUFFICIENT_FUNDS("insufficient-funds", HttpStatus.CONFLICT, "Insufficient funds"),

    /** The requested hold lifetime exceeds {@code ledger_config.max_hold_seconds} (ADR-004). */
    HOLD_TTL_TOO_LONG("hold-ttl-too-long", HttpStatus.UNPROCESSABLE_CONTENT, "Hold TTL too long"),

    /**
     * The currency on the request is not {@code ledger_config.currency} (ADR-004, ADR-011).
     *
     * <p>Catalogued ahead of the code that raises it, deliberately: ADR-011 names
     * {@code CURRENCY_MISMATCH} as an existing platform code, and a published catalogue that omits a
     * code callers already handle is worse than one entry that is not yet reachable. See the
     * currency note in {@code ReservationController}.
     */
    CURRENCY_MISMATCH("currency-mismatch", HttpStatus.UNPROCESSABLE_CONTENT, "Currency mismatch"),

    /**
     * The account row lock could not be taken inside {@code statement_timeout} (1s, set in
     * {@code application.yaml}).
     *
     * <p>The ledger's name for {@link CommonErrorType#RESOURCE_BUSY}: here the contended row is
     * always an account, the code is already published in this service's contract, and renaming it
     * would be a breaking change under ADR-011, §4. {@link LedgerApiExceptionHandler} is the
     * ten lines that keep it.
     *
     * <p>Head-of-line blocking on a hot account, not a failure of the request itself, so it is a 503
     * with {@code Retry-After} rather than a 500: the same request a moment later is very likely to
     * succeed. ADR-004 tracks it as {@code vaullet.row_lock.wait.p95}.
     */
    ACCOUNT_BUSY("account-busy", HttpStatus.SERVICE_UNAVAILABLE, "Account temporarily busy");

    private final URI type;
    private final HttpStatus status;
    private final String title;

    LedgerErrorType(String slug, HttpStatus status, String title) {
        this.type = ErrorType.documentationUri(slug);
        this.status = status;
        this.title = title;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public URI type() {
        return type;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String title() {
        return title;
    }
}
