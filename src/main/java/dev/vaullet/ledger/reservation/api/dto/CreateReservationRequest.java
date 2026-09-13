package dev.vaullet.ledger.reservation.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Request body for {@code POST /v1/reservations}.
 *
 * <p>A dedicated input type — rather than the domain record or the response — is what prevents
 * mass-assignment: there is simply no {@code reservation_id}, {@code state} or {@code allocations}
 * field for a caller to set. Constraints live on the record components, so a malformed request is
 * rejected before any ledger rule runs and before the account row is locked.
 *
 * <p><b>Money is a decimal string, never a JSON number</b> (ADR-011). {@code BigDecimal} binds
 * happily from {@code "60.00"}, and the string round-trips exactly through a JavaScript or Go
 * client, which a JSON number does not. {@code @Digits} mirrors the {@code NUMERIC(20,4)} column so
 * a value the database would reject is refused with a 400 naming the field rather than a 500.
 *
 * @param accountId the account to hold funds against
 * @param amount the amount to hold; must be positive, as {@code reservations.amount > 0} enforces
 * @param expiresInSeconds how long the hold survives uncaptured. Omitted means the deployment
 *     default of 300s. ADR-004 hands this to the caller because only the caller knows its
 *     settlement horizon: a card authorization resolves in milliseconds, a match-winner market in
 *     three days. The ceiling is {@code ledger_config.max_hold_seconds}.
 */
public record CreateReservationRequest(
        @NotNull @Schema(example = "3f1a8c7e-2b4d-4e6f-9a01-5c7d8e9f0a1b") UUID accountId,
        @NotNull
                @DecimalMin(value = "0.0001", message = "must be greater than zero")
                @Digits(integer = 16, fraction = 4)
                @Schema(type = "string", example = "60.00", description = "Decimal string, never a JSON number")
                BigDecimal amount,
        @Positive @Schema(example = "300", description = "Defaults to 300; ceiling is ledger_config.max_hold_seconds")
                @Nullable Integer expiresInSeconds) {}
