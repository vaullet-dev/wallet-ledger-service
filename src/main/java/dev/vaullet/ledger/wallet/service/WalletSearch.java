package dev.vaullet.ledger.wallet.service;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A wallet search: every filter is optional and they combine with AND.
 *
 * @param limit 1 to {@link #MAX_LIMIT}
 * @param cursor the {@code nextCursor} of the previous page, or null for the first
 */
public record WalletSearch(
        @Nullable UUID accountId,
        @Nullable WalletType type,
        @Nullable WalletStatus status,
        int limit,
        @Nullable String cursor) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;
}
