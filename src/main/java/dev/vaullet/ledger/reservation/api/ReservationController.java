package dev.vaullet.ledger.reservation.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import dev.vaullet.ledger.reservation.api.dto.CreateReservationRequest;
import dev.vaullet.ledger.reservation.api.dto.ReservationResponse;
import dev.vaullet.ledger.reservation.service.LedgerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * HTTP entry point for authorization holds — ADR-004's {@code /v1/reservations}.
 *
 * <p>The controller's whole job is translation: bind and validate the request, call one service
 * method, shape the response. There is no business logic, no transaction and no try/catch — errors
 * propagate to {@code ApiExceptionHandler}, which is the only place that knows about status codes.
 * That thinness is what lets the same rules be driven from the Kafka listener that settles a
 * completed transaction (ADR-004's revised flow, step 6) without a rewrite.
 *
 * <p>Conventions on display:
 *
 * <ul>
 *   <li><b>The major version is in the path</b> — {@code /v1/reservations}, per ADR-011, which
 *       rejected header versioning because a path is greppable in logs and a missing header is an
 *       implicit version nobody notices. This is a deliberate departure from the
 *       {@code @vaullet-dev} template's {@code X-API-Version} strategy.
 *   <li><b>Plural, noun-based resource paths.</b> The verb is the HTTP method: releasing a hold is
 *       {@code DELETE}, not {@code POST /releaseReservation}.
 *   <li><b>201 with a {@code Location} header</b> on create, 204 with no body on release.
 *   <li><b>{@code Idempotency-Key} is required, not optional.</b> ADR-011 makes it a platform rule
 *       for every state-changing POST, and here it is load-bearing rather than decorative: the
 *       reserve path is the one place the balance invariant is decided, so a retried request that
 *       placed a second hold would silently overstate what an account owes.
 * </ul>
 *
 * <p>The {@code @PreAuthorize} rules sit on these methods rather than on {@code LedgerService},
 * which is where the template puts them and where they ultimately belong — see the handover note in
 * {@link LedgerErrorBridge}.
 */
@Validated
@RestController
@RequestMapping("/v1/reservations")
@Tag(name = "Reservations", description = "Atomic check-and-hold against an account balance")
class ReservationController {

    private final LedgerService ledger;

    ReservationController(LedgerService ledger) {
        this.ledger = ledger;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_ledger:write')")
    @Operation(
            summary = "Place a hold",
            description = "Atomically checks the available balance and reserves against it. "
                    + "Retrying with the same Idempotency-Key returns the original hold.")
    @ApiResponse(responseCode = "201", description = "Hold placed, or the original hold replayed")
    @ApiResponse(responseCode = "409", description = "INSUFFICIENT_FUNDS")
    @ApiResponse(responseCode = "422", description = "HOLD_TTL_TOO_LONG")
    @ApiResponse(responseCode = "503", description = "ACCOUNT_BUSY — lock contention; retry")
    ResponseEntity<ReservationResponse> reserve(
            @Parameter(description = "Caller-generated key; a replay returns the original hold", required = true)
                    @RequestHeader("Idempotency-Key")
                    @NotBlank
                    @Size(max = 255)
                    String idempotencyKey,
            @Valid @RequestBody CreateReservationRequest request,
            UriComponentsBuilder uriBuilder) {

        var reservation = ledger.reserve(
                request.accountId(),
                request.amount(),
                idempotencyKey,
                // null means "the deployment default", which the service owns. Translating an
                // absent field into a default here would put the same policy in two places.
                request.expiresInSeconds() == null ? null : Duration.ofSeconds(request.expiresInSeconds()));

        URI location = uriBuilder
                .path("/v1/reservations/{id}")
                .buildAndExpand(reservation.reservationId())
                .toUri();

        // 201 on a replay too. The alternative — 200 for the second call — makes a caller's
        // retry path behave differently from its first attempt, which is the opposite of what
        // idempotency is for.
        return ResponseEntity.created(location).body(ReservationResponse.from(reservation));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_ledger:read')")
    @Operation(
            summary = "Fetch a hold",
            description = "State, amount, allocations and expiry — what a two-phase caller needs to see "
                    + "what it is currently holding.")
    @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND")
    ReservationResponse getById(@PathVariable UUID id) {
        return ReservationResponse.from(ledger.find(id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('SCOPE_ledger:write')")
    @Operation(
            summary = "Release a hold",
            description = "Returns the funds to the available balance. Writes no journal entry — money "
                    + "that never moved leaves no record. Releasing an already-resolved hold is a no-op, "
                    + "so a retry is safe.")
    @ApiResponse(responseCode = "204", description = "Released, or already resolved")
    @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND")
    void release(@PathVariable UUID id) {
        ledger.release(id);
    }
}
