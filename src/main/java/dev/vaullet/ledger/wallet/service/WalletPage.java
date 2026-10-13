package dev.vaullet.ledger.wallet.service;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One page of a wallet search. A page can hold fewer wallets than the limit and still not be the
 * last; only a null {@code nextCursor} ends the list (ADR-011 §8).
 */
public record WalletPage(List<Wallet> data, @Nullable String nextCursor) {}
