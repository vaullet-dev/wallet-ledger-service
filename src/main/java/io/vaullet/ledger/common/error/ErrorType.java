package io.vaullet.ledger.common.error;

import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * The catalogue of machine-readable error types this service can return.
 *
 * <p>RFC 9457 ({@code application/problem+json}) says the {@code type} URI is what a client branches
 * on — not the HTTP status, and certainly not a prose message. ADR-011 goes one step further and
 * makes a short {@code code} the contract instead, on the grounds that a code is greppable in a
 * support ticket and survives a documentation site move; the URI stays as the RFC-mandated
 * identifier. Enumerating both in one place means the set is reviewable, publishable in the OpenAPI
 * document, and impossible to typo at a call site.
 *
 * <p>Adding an entry is additive and safe. Changing what an existing entry <em>means</em> is a
 * breaking change for every caller that branches on it, so it needs a new major API version
 * (ADR-011, §4).
 */
public enum ErrorType {

    /** Request failed bean validation before any ledger rule ran. */
    VALIDATION_FAILED("validation-failed", HttpStatus.BAD_REQUEST, "Request validation failed"),

    /** The addressed account or reservation does not exist. */
    RESOURCE_NOT_FOUND("resource-not-found", HttpStatus.NOT_FOUND, "Resource not found"),

    /**
     * ADR-004's business signal, and the reason this service exists. A 409 rather than a 422:
     * the request is well-formed and would be valid against a different balance.
     */
    INSUFFICIENT_FUNDS("insufficient-funds", HttpStatus.CONFLICT, "Insufficient funds"),

    /** The requested hold lifetime exceeds {@code ledger_config.max_hold_seconds} (ADR-004). */
    HOLD_TTL_TOO_LONG("hold-ttl-too-long", HttpStatus.UNPROCESSABLE_CONTENT, "Hold TTL too long"),

    /**
     * The currency on the request is not {@code ledger_config.currency} (ADR-004, ADR-011).
     *
     * <p>Catalogued here ahead of the code that raises it, deliberately: ADR-011 names
     * {@code CURRENCY_MISMATCH} as an existing platform code, and a published catalogue that omits
     * a code callers already handle is worse than one entry that is not yet reachable. See the
     * currency note in {@code ReservationController}.
     */
    CURRENCY_MISMATCH("currency-mismatch", HttpStatus.UNPROCESSABLE_CONTENT, "Currency mismatch"),

    /**
     * The account row lock could not be taken inside {@code statement_timeout} (1s, set in
     * {@code application.yaml}).
     *
     * <p>This is head-of-line blocking on a hot account, not a failure of the request itself, so it
     * is a 503 with {@code Retry-After} rather than a 500: the same request a moment later is very
     * likely to succeed. ADR-004 tracks it as {@code vaullet.row_lock.wait.p95}.
     */
    ACCOUNT_BUSY("account-busy", HttpStatus.SERVICE_UNAVAILABLE, "Account temporarily busy"),

    ACCESS_DENIED("access-denied", HttpStatus.FORBIDDEN, "Access denied"),

    INTERNAL_ERROR("internal-error", HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");

    /** ADR-011, §7. Resolvable documentation, one page per code. */
    private static final String BASE = "https://docs.vaullet/errors/";

    private final URI type;
    private final HttpStatus status;
    private final String title;

    ErrorType(String slug, HttpStatus status, String title) {
        this.type = URI.create(BASE + slug);
        this.status = status;
        this.title = title;
    }

    public URI type() {
        return type;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    /** The stable, machine-readable contract (ADR-011): {@code INSUFFICIENT_FUNDS}, and so on. */
    public String code() {
        return name();
    }
}
