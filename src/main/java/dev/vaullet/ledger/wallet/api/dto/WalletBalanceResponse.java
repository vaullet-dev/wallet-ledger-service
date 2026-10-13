package dev.vaullet.ledger.wallet.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import dev.vaullet.ledger.wallet.service.WalletBalance;
import dev.vaullet.ledger.wallet.service.WalletStatus;
import dev.vaullet.ledger.wallet.service.WalletType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A wallet's money: its totals, and the buckets they are the sum of.
 *
 * <p>The totals answer the questions callers ask of a wallet and the buckets answer why, which is
 * ADR-004's per-bucket breakdown — scoped to one wallet rather than to the whole account, so a REAL
 * wallet's answer is never padded with every promotion the account ever received.
 *
 * <p>{@code available} and {@code withdrawable} are zero unless the wallet is {@code ACTIVE}, while
 * {@code posted} and {@code held} still show what is there. A frozen wallet's money exists and is
 * owed to the user; it just cannot be spent, and a figure called "available" that counted it would be
 * the one a caller acted on. For the same reason they leave out a grant past its expiry, which stays
 * posted until the sweeper forfeits it. Both are the buckets' figures added up.
 *
 * <p>{@code currency} is on the balance because ADR-004 returns it alongside every amount, so no
 * caller has to infer it from configuration. It is the deployment's single currency,
 * {@code ledger_config.currency}.
 */
@Schema(name = "WalletBalance", description = "A wallet's totals and the buckets they are summed over")
public record WalletBalanceResponse(
        @Schema(example = "0b9f3c1e-5d2a-4f8b-9c3d-7e6f5a4b3c2d") UUID walletId,
        @Schema(example = "7e57a11c-0000-4000-8000-000000000001") UUID accountId,
        @Schema(example = "REAL") WalletType type,
        @Schema(example = "ACTIVE") WalletStatus status,
        @Schema(example = "EUR", description = "ISO 4217, the deployment's single currency") String currency,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
                @Schema(type = "string", example = "100.00", description = "Over the spendable buckets; debt is separate")
                BigDecimal posted,
        @JsonFormat(shape = JsonFormat.Shape.STRING) @Schema(type = "string", example = "60.00") BigDecimal held,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
                @Schema(type = "string", example = "40.00", description = "What can be spent now: the buckets' available added up; zero unless the wallet is ACTIVE")
                BigDecimal available,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
                @Schema(
                        type = "string",
                        example = "40.00",
                        description = "The available money in withdrawable buckets; zero unless the wallet is ACTIVE")
                BigDecimal withdrawable,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
                @Schema(type = "string", example = "0.00", description = "What the user owes (ADR-009). Never spendable.")
                BigDecimal debt,
        @Schema(description = "Every bucket in the wallet, CASH first, then by spend priority")
                List<BucketBalanceResponse> buckets) {

    public static WalletBalanceResponse from(WalletBalance balance) {
        return new WalletBalanceResponse(
                balance.walletId(),
                balance.accountId(),
                balance.type(),
                balance.status(),
                balance.currency(),
                balance.posted(),
                balance.held(),
                balance.available(),
                balance.withdrawable(),
                balance.debt(),
                balance.buckets().stream().map(BucketBalanceResponse::from).toList());
    }
}
