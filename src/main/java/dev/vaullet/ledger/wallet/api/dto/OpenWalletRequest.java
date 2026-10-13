package dev.vaullet.ledger.wallet.api.dto;

import dev.vaullet.ledger.wallet.service.WalletType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

/**
 * Request body for {@code POST /v1/wallets}.
 *
 * <p><b>No {@code Idempotency-Key}.</b> ADR-011 makes the header a platform rule for state-changing
 * POSTs, and this request already carries a natural key: an account has at most one wallet of each
 * type, so repeating the request returns the wallet the first one opened rather than a second. That
 * is the argument auth-service makes for {@code POST /v1/accounts} and its {@code external_ref}, and
 * a header would only restate the body.
 *
 * <p><b>{@code type} is a string with a pattern, not the {@link WalletType} enum.</b> An enum would
 * refuse an unknown value inside Jackson, before validation runs, and Spring answers that with a 400
 * that has no {@code code}. Checked here, an unknown type gets the same {@code VALIDATION_FAILED} body
 * naming the field as a missing one. OpenAPI still lists the two values.
 *
 * @param accountId whose wallet. The ledger opens the account too, if this is its first wallet.
 */
public record OpenWalletRequest(
        @NotNull @Schema(example = "7e57a11c-0000-4000-8000-000000000001") UUID accountId,
        @NotNull
                @Pattern(regexp = "REAL|BONUS", message = "must be REAL or BONUS")
                @Schema(allowableValues = {"REAL", "BONUS"}, example = "REAL")
                String type) {

    /** The type as the service takes it. Only after validation, which has ruled out anything else. */
    public WalletType walletType() {
        return WalletType.valueOf(type);
    }
}
