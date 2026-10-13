package dev.vaullet.ledger.wallet.service;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A wallet: one of an account's money containers. No amounts here; see {@link WalletBalance}. */
public record Wallet(
        UUID walletId,
        UUID accountId,
        WalletType type,
        WalletStatus status,
        Instant createdAt,
        Instant updatedAt,
        @Nullable Instant closedAt) {}
