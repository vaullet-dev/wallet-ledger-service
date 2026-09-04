package io.vaullet.ledger.reservation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import io.vaullet.ledger.config.MethodSecurityConfig;
import io.vaullet.ledger.config.SecurityConfig;
import io.vaullet.ledger.reservation.service.LedgerService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Web-layer slice: real routing, real validation, real security filters, real error handling — with
 * the ledger mocked out.
 *
 * <p>{@code @WebMvcTest} starts the MVC infrastructure and nothing else, so these run on every save
 * and a failure points at the web layer rather than at PostgreSQL. That is the argument for slices:
 * a suite made only of full-context tests is slow and, when it breaks, tells you nothing about
 * where.
 *
 * <p>{@code SecurityConfig} is imported rather than disabled. Testing a controller with
 * {@code addFilters = false} verifies an application that will never be deployed; the 401 and the
 * 403 below are the interesting assertions, not incidental ones.
 *
 * <p>What is deliberately <em>not</em> tested here: whether a reservation is correct. That is
 * {@code OverdraftIT}'s job, against a real database, because the invariant lives in the SQL. These
 * tests only prove the HTTP contract around it.
 */
@WebMvcTest(ReservationController.class)
@Import({SecurityConfig.class, MethodSecurityConfig.class})
class ReservationControllerTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RESERVATION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID BUCKET_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final String READ = "SCOPE_ledger:read";
    private static final String WRITE = "SCOPE_ledger:write";

    private static final String BODY = """
            {"account_id":"11111111-1111-1111-1111-111111111111","amount":"60.00"}""";

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private LedgerService ledger;

    /** Required to assemble the production filter chain; never invoked — see jwt() below. */
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("rejects an unauthenticated request before it reaches the controller")
    void requiresAuthentication() {
        assertThat(mvc.get().uri("/v1/reservations/{id}", RESERVATION_ID)).hasStatus(401);
    }

    @Test
    @DisplayName("a valid token without the write scope cannot place a hold")
    void requiresTheWriteScope() {
        assertThat(mvc.post()
                        .uri("/v1/reservations")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY)
                        .with(jwt().authorities(() -> READ)))
                .hasStatus(403);

        // The important half of the assertion: authorisation ran before the ledger did.
        verify(ledger, never()).reserve(any(), any(), any(), any());
    }

    @Test
    @DisplayName("returns 201 with a Location header pointing at the new hold")
    void placesAHold() {
        when(ledger.reserve(eq(ACCOUNT_ID), eq(new BigDecimal("60.00")), eq("key-1"), eq(null)))
                .thenReturn(heldReservation());

        assertThat(mvc.post()
                        .uri("/v1/reservations")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY)
                        .with(jwt().authorities(() -> WRITE)))
                .hasStatus(201)
                .headers()
                .hasHeaderSatisfying("Location", values -> assertThat(values.getFirst())
                        .endsWith("/v1/reservations/" + RESERVATION_ID));
    }

    @Test
    @DisplayName("money is a decimal string and every key is snake_case — ADR-011")
    void serialisesMoneyAsAStringInSnakeCase() {
        when(ledger.reserve(any(), any(), any(), any())).thenReturn(heldReservation());

        var result = assertThat(mvc.post()
                        .uri("/v1/reservations")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY)
                        .with(jwt().authorities(() -> WRITE)))
                .hasStatus(201);

        // A JSON number here would be a rounding defect waiting for a client with binary floats.
        result.bodyJson().extractingPath("$.amount").isEqualTo("60.0000");
        result.bodyJson().extractingPath("$.reservation_id").isEqualTo(RESERVATION_ID.toString());
        result.bodyJson().extractingPath("$.allocations[0].bucket_type").isEqualTo("CASH");
    }

    @Test
    @DisplayName("an absent expires_in_seconds is passed on as null, not defaulted in the web layer")
    void leavesTheHoldDefaultToTheService() {
        when(ledger.reserve(any(), any(), any(), any())).thenReturn(heldReservation());

        assertThat(mvc.post()
                        .uri("/v1/reservations")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY)
                        .with(jwt().authorities(() -> WRITE)))
                .hasStatus(201);

        // Defaulting here as well as in the service would put one policy in two places.
        verify(ledger).reserve(ACCOUNT_ID, new BigDecimal("60.00"), "key-1", null);
    }

    @Test
    @DisplayName("a supplied expires_in_seconds reaches the service as a Duration")
    void passesTheCallerSuppliedTtlThrough() {
        when(ledger.reserve(any(), any(), any(), any())).thenReturn(heldReservation());

        assertThat(mvc.post()
                        .uri("/v1/reservations")
                        .header("Idempotency-Key", "wager")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"account_id":"11111111-1111-1111-1111-111111111111",\
                                "amount":"20.00","expires_in_seconds":259200}""")
                        .with(jwt().authorities(() -> WRITE)))
                .hasStatus(201);

        verify(ledger).reserve(ACCOUNT_ID, new BigDecimal("20.00"), "wager", Duration.ofDays(3));
    }

    @Test
    @DisplayName("a POST without an Idempotency-Key is refused — ADR-011 makes it mandatory")
    void requiresAnIdempotencyKey() {
        assertThat(mvc.post()
                        .uri("/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY)
                        .with(jwt().authorities(() -> WRITE)))
                .hasStatus(400);

        verify(ledger, never()).reserve(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a non-positive amount is rejected with field-level errors, before the ledger runs")
    void validatesTheAmount() {
        var result = assertThat(mvc.post()
                        .uri("/v1/reservations")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"account_id":"11111111-1111-1111-1111-111111111111","amount":"0"}""")
                        .with(jwt().authorities(() -> WRITE)))
                .hasStatus(400);

        result.bodyJson().extractingPath("$.errors[*].field").asArray().contains("amount");
        verify(ledger, never()).reserve(any(), any(), any(), any());
    }

    @Test
    @DisplayName("insufficient funds is a 409 carrying the stable INSUFFICIENT_FUNDS code")
    void mapsInsufficientFundsToConflict() {
        when(ledger.reserve(any(), any(), any(), any()))
                .thenThrow(new LedgerService.InsufficientFunds("available 40.0000 < requested 60.0000"));

        var result = assertThat(mvc.post()
                        .uri("/v1/reservations")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY)
                        .with(jwt().authorities(() -> WRITE)))
                .hasStatus(409)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);

        // `code` is the contract a caller branches on; `detail` is for humans (ADR-011, §7).
        result.bodyJson().extractingPath("$.code").isEqualTo("INSUFFICIENT_FUNDS");
        result.bodyJson().extractingPath("$.type").isEqualTo("https://docs.vaullet/errors/insufficient-funds");
    }

    @Test
    @DisplayName("a hold longer than the deployment ceiling is a 422, not a 400 or a 500")
    void mapsHoldTtlTooLongToUnprocessable() {
        when(ledger.reserve(any(), any(), any(), any()))
                .thenThrow(new LedgerService.HoldTtlTooLong("34560000s exceeds max_hold_seconds 2592000"));

        var result = assertThat(mvc.post()
                        .uri("/v1/reservations")
                        .header("Idempotency-Key", "forever")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"account_id":"11111111-1111-1111-1111-111111111111",\
                                "amount":"1.00","expires_in_seconds":34560000}""")
                        .with(jwt().authorities(() -> WRITE)))
                .hasStatus(422);

        result.bodyJson().extractingPath("$.code").isEqualTo("HOLD_TTL_TOO_LONG");
    }

    @Test
    @DisplayName("releasing a hold answers 204 with no body")
    void releasesAHold() {
        assertThat(mvc.delete()
                        .uri("/v1/reservations/{id}", RESERVATION_ID)
                        .with(jwt().authorities(() -> WRITE)))
                .hasStatus(204);

        verify(ledger).release(RESERVATION_ID);
    }

    private static LedgerService.Reservation heldReservation() {
        return new LedgerService.Reservation(
                RESERVATION_ID,
                new BigDecimal("60.0000"),
                "HELD",
                Instant.parse("2026-01-01T00:05:00Z"),
                List.of(new LedgerService.Allocation(BUCKET_ID, "CASH", new BigDecimal("60.0000"))));
    }
}
