package dev.vaullet.ledger.wallet.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import dev.vaullet.ledger.wallet.service.WalletBalance;
import dev.vaullet.ledger.wallet.service.WalletStatus;
import dev.vaullet.ledger.wallet.service.WalletType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One wallet's totals, as an account balance lists them.
 *
 * <p>{@link WalletBalanceResponse} without the buckets, and without the account id and currency the
 * account balance already carries once. The buckets are left out because an account balance is read
 * often and a BONUS wallet keeps a bucket for every grant it ever received; a caller that wants them
 * asks for that one wallet.
 */
@Schema(name = "WalletSummary", description = "One wallet's totals; its buckets are on GET /v1/wallets/{id}/balance")
public record WalletSummaryResponse(
        @Schema(example = "0b9f3c1e-5d2a-4f8b-9c3d-7e6f5a4b3c2d") UUID walletId,
        @Schema(example = "REAL") WalletType type,
        @Schema(example = "ACTIVE") WalletStatus status,
        @JsonFormat(shape = JsonFormat.Shape.STRING) @Schema(type = "string", example = "100.00") BigDecimal posted,
        @JsonFormat(shape = JsonFormat.Shape.STRING) @Schema(type = "string", example = "60.00") BigDecimal held,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
                @Schema(type = "string", example = "40.00", description = "What can be spent now; zero unless the wallet is ACTIVE")
                BigDecimal available,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
                @Schema(type = "string", example = "40.00", description = "Zero unless the wallet is ACTIVE")
                BigDecimal withdrawable,
        @JsonFormat(shape = JsonFormat.Shape.STRING) @Schema(type = "string", example = "0.00") BigDecimal debt) {

    public static WalletSummaryResponse from(WalletBalance balance) {
        return new WalletSummaryResponse(
                balance.walletId(),
                balance.type(),
                balance.status(),
                balance.posted(),
                balance.held(),
                balance.available(),
                balance.withdrawable(),
                balance.debt());
    }
}
