package dev.vaullet.ledger.wallet.api.dto;

import dev.vaullet.ledger.wallet.service.WalletStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for {@code PATCH /v1/wallets/{id}}: the freeze, and lifting it.
 *
 * <p><b>{@code CLOSED} is not accepted here.</b> Closing is {@code DELETE}, because it is the one
 * change with a precondition — the wallet must be empty — and no way back. Refused at validation, a
 * {@code CLOSED} never reaches {@code WalletService.changeStatus}, which would otherwise have to answer
 * a caller's mistake with an {@code IllegalArgumentException} and a 500.
 *
 * <p>A string with a pattern rather than the {@link WalletStatus} enum, for the reason
 * {@link OpenWalletRequest} gives: a missing status, an unknown one and {@code CLOSED} all come back as
 * the same {@code VALIDATION_FAILED} naming the field, instead of one of them being a 400 without a
 * {@code code} from Jackson.
 */
public record UpdateWalletStatusRequest(
        @NotNull
                @Pattern(regexp = "ACTIVE|FROZEN", message = "must be ACTIVE or FROZEN; closing a wallet is DELETE")
                @Schema(
                        allowableValues = {"ACTIVE", "FROZEN"},
                        example = "FROZEN",
                        description = "FROZEN stops the money being spent; ACTIVE lifts the freeze")
                String status) {

    /** The status as the service takes it. Only after validation, which has ruled out anything else. */
    public WalletStatus walletStatus() {
        return WalletStatus.valueOf(status);
    }
}
