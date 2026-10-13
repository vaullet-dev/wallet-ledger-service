package dev.vaullet.ledger.reservation.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import dev.vaullet.ledger.reservation.service.LedgerService;
import dev.vaullet.ledger.wallet.api.dto.WalletSummaryResponse;
import dev.vaullet.ledger.wallet.service.WalletBalance;
import java.math.BigDecimal;
import java.util.List;

/**
 * An account's money, in the four figures that are not derivable from one another, and the same
 * money wallet by wallet.
 *
 * <p>{@code posted} minus {@code held} is {@code available}, and it is returned anyway: it is the
 * number every caller actually wants, and making each of them compute it is how one of them
 * eventually computes it wrong.
 *
 * <p>{@code withdrawable} is the distinction a single balance cannot express, and the reason
 * ADR-004 models buckets at all — promotional money can be staked but not withdrawn.
 * {@code debt} is a positive amount the user owes (ADR-009); it is reported here but is never
 * spendable, which is why it does not appear in {@code available}.
 *
 * <p>{@code available} and {@code withdrawable} count only the money a hold could draw on — in
 * {@code ACTIVE} wallets, and not in a grant past its expiry — so they are the sums of the same
 * figures over {@code wallets}. {@code posted}, {@code held} and {@code debt} stay the account's
 * totals: a frozen wallet's money is still the user's, it just cannot be spent, and a lapsed grant
 * stays posted until the sweeper forfeits it.
 *
 * <p>{@code currency} because ADR-004 returns it alongside every amount, so no caller has to infer it
 * from configuration. One currency per deployment, {@code ledger_config.currency}.
 *
 * <p>{@code wallets} carries each wallet's totals without its buckets; {@code GET
 * /v1/wallets/{id}/balance} has those. Closed wallets are listed too — they are part of the account's
 * history and hold nothing.
 */
@Schema(name = "Balance", description = "An account's posted, held, available, withdrawable and debt totals, and each wallet's")
public record BalanceResponse(
        @Schema(example = "EUR", description = "ISO 4217, the deployment's single currency") String currency,
        @JsonFormat(shape = JsonFormat.Shape.STRING) @Schema(type = "string", example = "100.00") BigDecimal posted,
        @JsonFormat(shape = JsonFormat.Shape.STRING) @Schema(type = "string", example = "60.00") BigDecimal held,
        @JsonFormat(shape = JsonFormat.Shape.STRING) @Schema(type = "string", example = "40.00") BigDecimal available,
        @JsonFormat(shape = JsonFormat.Shape.STRING) @Schema(type = "string", example = "40.00")
                BigDecimal withdrawable,
        @JsonFormat(shape = JsonFormat.Shape.STRING) @Schema(type = "string", example = "0.00") BigDecimal debt,
        @Schema(description = "Every wallet of the account, REAL first, closed ones included")
                List<WalletSummaryResponse> wallets) {

    public static BalanceResponse from(LedgerService.Balance balance, String currency, List<WalletBalance> wallets) {
        return new BalanceResponse(
                currency,
                balance.posted(),
                balance.held(),
                balance.available(),
                balance.withdrawable(),
                balance.debt(),
                wallets.stream().map(WalletSummaryResponse::from).toList());
    }
}
