package dev.vaullet.ledger.common.error;

import dev.vaullet.common.error.ApplicationException;
import java.io.Serial;
import java.math.BigDecimal;

/**
 * The available balance cannot fund the requested hold — ADR-004's central invariant, refusing.
 *
 * <p>Worth saying explicitly: this is a <em>business signal</em>, not a fault. ADR-004 monitors it
 * as {@code vaullet.reserve.rejected{reason=INSUFFICIENT_FUNDS}} and expects a non-zero rate
 * forever. It is logged at debug and must never page anyone.
 *
 * <p>The message names both figures because a caller reconciling its own view of the balance needs
 * to see the difference; neither is a secret, both belong to the account the caller just addressed.
 */
public class InsufficientFundsException extends ApplicationException {

    @Serial
    private static final long serialVersionUID = 1L;

    public InsufficientFundsException(BigDecimal available, BigDecimal requested) {
        super(
                LedgerErrorType.INSUFFICIENT_FUNDS,
                "Available balance %s is less than the requested %s".formatted(available, requested));
    }

    public InsufficientFundsException(String message) {
        super(LedgerErrorType.INSUFFICIENT_FUNDS, message);
    }
}
