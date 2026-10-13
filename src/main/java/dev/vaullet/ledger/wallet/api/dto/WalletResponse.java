package dev.vaullet.ledger.wallet.api.dto;

import dev.vaullet.ledger.wallet.service.Wallet;
import dev.vaullet.ledger.wallet.service.WalletStatus;
import dev.vaullet.ledger.wallet.service.WalletType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A wallet, as the API represents it: what it is and what state it is in, and no money.
 *
 * <p>One representation serves the create, the read, the list and the status change, for the
 * reason {@code ReservationResponse} gives: a caller that retries or polls wants exactly what the
 * first call returned.
 *
 * <p>Amounts are deliberately not here. They live on the wallet's buckets and move with every hold,
 * so they are a separate, advisory read ({@code GET /v1/wallets/{id}/balance}); putting them on this
 * representation would put a stale balance on every list page.
 *
 * <p>{@code updated_at} is when the wallet itself last changed — opened, frozen, unfrozen, closed.
 * Money moving in or out does not touch it.
 */
@Schema(name = "Wallet", description = "One of an account's money containers. Its money is on its balance.")
public record WalletResponse(
        @Schema(example = "0b9f3c1e-5d2a-4f8b-9c3d-7e6f5a4b3c2d") UUID walletId,
        @Schema(example = "7e57a11c-0000-4000-8000-000000000001") UUID accountId,
        @Schema(example = "REAL", description = "REAL holds cash; BONUS holds promotional grants") WalletType type,
        @Schema(example = "ACTIVE", description = "FROZEN money cannot be spent; CLOSED is terminal") WalletStatus status,
        @Schema(example = "2026-10-13T09:15:00Z") Instant createdAt,
        @Schema(example = "2026-10-13T09:15:00Z", description = "When the wallet itself last changed, not its money")
                Instant updatedAt,
        @Schema(example = "2026-10-20T16:40:00Z", description = "When it was closed. Absent until it is CLOSED.")
                @Nullable Instant closedAt) {

    public static WalletResponse from(Wallet wallet) {
        return new WalletResponse(
                wallet.walletId(),
                wallet.accountId(),
                wallet.type(),
                wallet.status(),
                wallet.createdAt(),
                wallet.updatedAt(),
                wallet.closedAt());
    }
}
