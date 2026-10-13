package dev.vaullet.ledger.wallet.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import dev.vaullet.ledger.wallet.service.BucketBalance;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One ADR-004 bucket inside a wallet: a grant, or the account's cash.
 *
 * <p>{@code available} means here what it means on the wallet and on the account: what this bucket
 * can fund now. That is its {@code posted - held}, except that DEBT funds nothing (it is owed, not
 * held), a grant past its expiry funds nothing (the sweeper is about to forfeit it), and no bucket of
 * a frozen or closed wallet funds anything. A wallet's {@code available} is therefore its buckets'
 * added up, and a caller after the raw figure has {@code posted} and {@code held}.
 *
 * <p>{@code withdrawable} is a flag here and an amount on the wallet. A bucket either is withdrawable
 * money or is not — cash is, promotional money is not while wagering is outstanding — and the wallet's
 * figure is the available money in the buckets that are, while the wallet is {@code ACTIVE}.
 */
@Schema(name = "BucketBalance", description = "One bucket of a wallet: a grant, or the account's cash")
public record BucketBalanceResponse(
        @Schema(example = "5c2e8a71-3f4b-4d6e-8a9b-1c2d3e4f5a6b") UUID bucketId,
        @Schema(example = "CASH", allowableValues = {"CASH", "BONUS", "LOYALTY", "REFERRAL", "DEBT"}) String type,
        @JsonFormat(shape = JsonFormat.Shape.STRING) @Schema(type = "string", example = "100.00") BigDecimal posted,
        @JsonFormat(shape = JsonFormat.Shape.STRING) @Schema(type = "string", example = "60.00") BigDecimal held,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
                @Schema(type = "string", example = "40.00", description = "What this bucket can fund now: posted - held, or zero for DEBT, a grant past its expiry and any bucket of a wallet that is not ACTIVE")
                BigDecimal available,
        @Schema(example = "true", description = "Whether this bucket's money can be withdrawn") boolean withdrawable,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
                @Schema(
                        type = "string",
                        example = "0.00",
                        description = "Wagering still outstanding on this grant. The rewards module that granted it owns the rule.")
                BigDecimal wageringRemaining,
        @Schema(example = "2026-11-12T23:59:59Z", description = "When the grant lapses. Absent for money that does not.")
                @Nullable Instant expiresAt) {

    static BucketBalanceResponse from(BucketBalance bucket) {
        return new BucketBalanceResponse(
                bucket.bucketId(),
                bucket.bucketType(),
                bucket.posted(),
                bucket.held(),
                bucket.available(),
                bucket.withdrawable(),
                bucket.wageringRemaining(),
                bucket.expiresAt());
    }
}
