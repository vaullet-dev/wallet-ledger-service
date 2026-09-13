package dev.vaullet.ledger.reservation.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import dev.vaullet.ledger.reservation.service.LedgerService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A hold, as the API represents it.
 *
 * <p>One representation serves both {@code POST /v1/reservations} and
 * {@code GET /v1/reservations/{id}}. ADR-004 describes the two payloads separately, but they differ
 * only in which fields it bothered to list, and a two-phase caller polling the GET wants exactly
 * what the POST returned. Two near-identical types would be two things to keep in step.
 *
 * <p>{@code expiresAt} is an absolute RFC 3339 instant rather than the relative
 * {@code expires_in_seconds} the request takes: the caller knows when it sent the request, but only
 * the ledger knows when the transaction committed, and on a contended account those differ by
 * enough to matter to a client deciding when to re-check.
 *
 * @param state one of {@code HELD}, {@code SETTLED}, {@code RELEASED}, {@code EXPIRED}
 */
@Schema(name = "Reservation", description = "An authorization hold against an account")
public record ReservationResponse(
        UUID reservationId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) @Schema(type = "string", example = "60.00") BigDecimal amount,
        @Schema(example = "HELD") String state,
        Instant expiresAt,
        List<AllocationResponse> allocations) {

    public static ReservationResponse from(LedgerService.Reservation reservation) {
        return new ReservationResponse(
                reservation.reservationId(),
                reservation.amount(),
                reservation.state(),
                reservation.expiresAt(),
                reservation.allocations().stream().map(AllocationResponse::from).toList());
    }
}
