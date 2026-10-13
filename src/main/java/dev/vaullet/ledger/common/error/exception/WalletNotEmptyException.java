package dev.vaullet.ledger.common.error.exception;

import dev.vaullet.common.error.ApplicationException;
import dev.vaullet.ledger.common.error.LedgerErrorType;
import java.io.Serial;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A wallet can only be closed at zero: no posted money, nothing held, no debt. Money in a closed
 * wallet could neither be spent nor withdrawn, so the ledger refuses rather than strand it.
 */
public class WalletNotEmptyException extends ApplicationException {

    @Serial
    private static final long serialVersionUID = 1L;

    public WalletNotEmptyException(UUID walletId, BigDecimal posted, BigDecimal held, BigDecimal debt) {
        super(
                LedgerErrorType.WALLET_NOT_EMPTY,
                "Wallet %s still has posted %s, held %s and debt %s".formatted(walletId, posted, held, debt));
    }
}
