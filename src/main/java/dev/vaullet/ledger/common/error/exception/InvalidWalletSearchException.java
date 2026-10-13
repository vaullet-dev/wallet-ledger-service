package dev.vaullet.ledger.common.error.exception;

import dev.vaullet.common.error.ApplicationException;
import dev.vaullet.common.error.CommonErrorType;
import java.io.Serial;

/**
 * A wallet search the service cannot run: a cursor it did not issue, or a limit out of range. The
 * platform's {@code VALIDATION_FAILED}, like any other malformed request: callers branch on that
 * code, not on this type.
 */
public class InvalidWalletSearchException extends ApplicationException {

    @Serial
    private static final long serialVersionUID = 1L;

    public InvalidWalletSearchException(String message) {
        super(CommonErrorType.VALIDATION_FAILED, message);
    }
}
