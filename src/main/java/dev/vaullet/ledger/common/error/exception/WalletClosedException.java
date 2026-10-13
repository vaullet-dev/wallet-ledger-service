package dev.vaullet.ledger.common.error.exception;

import dev.vaullet.common.error.ApplicationException;
import dev.vaullet.ledger.common.error.LedgerErrorType;
import java.io.Serial;
import java.util.UUID;

/**
 * The wallet is closed, and closing is terminal: it takes no new money, cannot be frozen or
 * unfrozen, and the account cannot open another wallet of the same type.
 */
public class WalletClosedException extends ApplicationException {

    @Serial
    private static final long serialVersionUID = 1L;

    public WalletClosedException(UUID walletId) {
        super(LedgerErrorType.WALLET_CLOSED, "Wallet %s is closed".formatted(walletId));
    }
}
