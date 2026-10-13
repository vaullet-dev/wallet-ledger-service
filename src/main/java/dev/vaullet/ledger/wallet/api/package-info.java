/**
 * The wallet resource, {@code /v1/wallets}: bind a request, call one {@code WalletService} method,
 * shape the response. No rules, no transaction and no try/catch here; a refusal reaches the shared
 * advice as an {@code ApplicationException} carrying its code.
 *
 * <p>{@link org.jspecify.annotations.NullMarked} is declared here and not inherited from the parent
 * package: Java packages do not nest for annotation purposes.
 */
@NullMarked
package dev.vaullet.ledger.wallet.api;

import org.jspecify.annotations.NullMarked;
