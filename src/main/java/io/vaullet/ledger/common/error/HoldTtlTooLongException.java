package io.vaullet.ledger.common.error;

import io.vaullet.common.error.ApplicationException;
import java.io.Serial;
import java.time.Duration;

/**
 * The caller asked to hold funds for longer than this deployment permits.
 *
 * <p>ADR-004 hands the hold lifetime to the caller — only the caller knows whether it is settling a
 * card authorization in milliseconds or a match-winner market in three days — and bounds it with
 * {@code ledger_config.max_hold_seconds}. The ceiling lives in the database rather than in
 * configuration because it is a property of the ledger's data, enforced alongside the money it
 * governs.
 */
public class HoldTtlTooLongException extends ApplicationException {

    @Serial
    private static final long serialVersionUID = 1L;

    public HoldTtlTooLongException(Duration requested, long maxHoldSeconds) {
        super(
                LedgerErrorType.HOLD_TTL_TOO_LONG,
                "A hold of %ds exceeds this deployment's ceiling of %ds"
                        .formatted(requested.getSeconds(), maxHoldSeconds));
    }

    public HoldTtlTooLongException(String message) {
        super(LedgerErrorType.HOLD_TTL_TOO_LONG, message);
    }
}
