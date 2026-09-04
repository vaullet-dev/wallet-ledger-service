package io.vaullet.ledger.reservation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import io.vaullet.ledger.support.IntegrationTest;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * End-to-end through every layer: HTTP in, PostgreSQL out.
 *
 * <p>These catch what the slice and unit tests structurally cannot — a wrong column name, a
 * transaction boundary in the wrong place, a security rule that only fails once the real chain is
 * assembled, a Jackson configuration that serialises the wrong shape. {@code OverdraftIT} proves the
 * ledger is correct; this proves the API in front of it exposes that correctness faithfully.
 *
 * <p>They are the expensive tests, so the suite covers journeys rather than permutations. The
 * {@code *IT} suffix binds them to Failsafe and {@code mvn verify}, keeping {@code mvn test} fast
 * enough to run constantly.
 */
@IntegrationTest
class ReservationApiIT {

    private static final String READ = "SCOPE_ledger:read";
    private static final String WRITE = "SCOPE_ledger:write";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID account;

    @BeforeEach
    void seedAnAccountWithCash() {
        // TRUNCATE, not DELETE: the append-only rule on ledger_entries makes DELETE a no-op, which
        // then blocks removing the buckets those entries reference. The journal defending itself
        // against the test fixture is the rule working exactly as intended.
        jdbc.update(
                """
                TRUNCATE ledger_entries, reservation_allocations, reservations,
                         balance_buckets, account_balances, ledger_config
                """);
        jdbc.update("INSERT INTO ledger_config (currency, minor_units) VALUES ('EUR', 2)");

        account = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO account_balances (account_id, posted_balance) VALUES (?, ?)",
                account,
                new BigDecimal("100.0000"));
        jdbc.update(
                """
                INSERT INTO balance_buckets (bucket_id, account_id, bucket_type, posted_balance,
                                             withdrawable, spend_priority)
                VALUES (?, ?, 'CASH', ?, TRUE, 100)
                """,
                UUID.randomUUID(),
                account,
                new BigDecimal("100.0000"));
    }

    @Test
    @DisplayName("places a hold, reads it back, releases it, and the money returns")
    void theFullHoldLifecycle() {
        var created = assertThat(post("hold-1", "60.00")).hasStatus(201);
        String reservationId =
                created.bodyJson().extractingPath("$.reservation_id").asString().actual();

        created.bodyJson().extractingPath("$.state").isEqualTo("HELD");
        created.bodyJson().extractingPath("$.allocations[0].bucket_type").isEqualTo("CASH");

        assertThat(mvc.get()
                        .uri("/v1/accounts/{id}/balance", account)
                        .with(jwt().authorities(() -> READ)))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.available")
                .isEqualTo("40.0000");

        assertThat(mvc.get().uri("/v1/reservations/{id}", reservationId).with(jwt().authorities(() -> READ)))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.state")
                .isEqualTo("HELD");

        assertThat(mvc.delete()
                        .uri("/v1/reservations/{id}", reservationId)
                        .with(jwt().authorities(() -> WRITE)))
                .hasStatus(204);

        assertThat(mvc.get()
                        .uri("/v1/accounts/{id}/balance", account)
                        .with(jwt().authorities(() -> READ)))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.available")
                .isEqualTo("100.0000");
    }

    @Test
    @DisplayName("a second hold the balance cannot fund is a 409 INSUFFICIENT_FUNDS, not a 500")
    void refusesAnOverdraftThroughTheApi() {
        assertThat(post("hold-1", "60.00")).hasStatus(201);

        var refused = assertThat(post("hold-2", "60.00"))
                .hasStatus(409)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);

        refused.bodyJson().extractingPath("$.code").isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    @DisplayName("a retried POST with the same Idempotency-Key returns the original hold")
    void replaysRatherThanHoldingTwice() {
        var first = assertThat(post("same-key", "60.00")).hasStatus(201);
        var retry = assertThat(post("same-key", "60.00")).hasStatus(201);

        assertThat(retry.bodyJson().extractingPath("$.reservation_id").asString().actual())
                .isEqualTo(first.bodyJson().extractingPath("$.reservation_id").asString().actual());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservations", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the whole surface is closed to an unauthenticated caller")
    void deniesUnauthenticatedAccess() {
        assertThat(mvc.get().uri("/v1/accounts/{id}/balance", account)).hasStatus(401);
        assertThat(mvc.post()
                        .uri("/v1/reservations")
                        .header("Idempotency-Key", "anon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("10.00")))
                .hasStatus(401);
    }

    @Test
    @DisplayName("an unknown reservation is a 404 problem document, not a stack trace")
    void reportsAnUnknownReservation() {
        var result = assertThat(mvc.get()
                        .uri("/v1/reservations/{id}", UUID.randomUUID())
                        .with(jwt().authorities(() -> READ)))
                .hasStatus(404);

        result.bodyJson().extractingPath("$.code").isEqualTo("RESOURCE_NOT_FOUND");
    }

    private org.springframework.test.web.servlet.assertj.MvcTestResult post(String key, String amount) {
        return mvc.post()
                .uri("/v1/reservations")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(amount))
                .with(jwt().authorities(() -> WRITE))
                .exchange();
    }

    private String body(String amount) {
        return """
                {"account_id":"%s","amount":"%s"}""".formatted(account, amount);
    }
}
