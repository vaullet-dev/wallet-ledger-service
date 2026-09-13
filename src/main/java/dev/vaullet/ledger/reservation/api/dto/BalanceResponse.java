package dev.vaullet.ledger.reservation.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import dev.vaullet.ledger.reservation.service.LedgerService;
import java.math.BigDecimal;

/**
 * An account's money, in the four figures that are not derivable from one another.
 *
 * <p>{@code posted} minus {@code held} is {@code available}, and it is returned anyway: it is the
 * number every caller actually wants, and making each of them compute it is how one of them
 * eventually computes it wrong.
 *
 * <p>{@code withdrawable} is the distinction a single balance cannot express, and the reason
 * ADR-004 models buckets at all — promotional money can be staked but not withdrawn.
 * {@code debt} is a positive amount the user owes (ADR-009); it is reported here but is never
 * spendable, which is why it does not appear in {@code available}.
 */
@Schema(name = "Balance", description = "An account's posted, held, available, withdrawable and debt totals")
public record BalanceResponse(
        @JsonFormat(shape = JsonFormat.Shape.STRING) @Schema(type = "string", example = "100.00") BigDecimal posted,
        @JsonFormat(shape = JsonFormat.Shape.STRING) @Schema(type = "string", example = "60.00") BigDecimal held,
        @JsonFormat(shape = JsonFormat.Shape.STRING) @Schema(type = "string", example = "40.00") BigDecimal available,
        @JsonFormat(shape = JsonFormat.Shape.STRING) @Schema(type = "string", example = "40.00")
                BigDecimal withdrawable,
        @JsonFormat(shape = JsonFormat.Shape.STRING) @Schema(type = "string", example = "0.00") BigDecimal debt) {

    public static BalanceResponse from(LedgerService.Balance balance) {
        return new BalanceResponse(
                balance.posted(), balance.held(), balance.available(), balance.withdrawable(), balance.debt());
    }
}
