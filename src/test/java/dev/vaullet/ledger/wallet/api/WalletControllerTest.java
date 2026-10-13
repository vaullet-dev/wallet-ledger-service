package dev.vaullet.ledger.wallet.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import dev.vaullet.common.error.ApplicationException;
import dev.vaullet.common.error.CommonErrorType;
import dev.vaullet.common.error.exception.ResourceNotFoundException;
import dev.vaullet.common.security.PlatformSecurityAutoConfiguration;
import dev.vaullet.ledger.common.error.exception.WalletClosedException;
import dev.vaullet.ledger.common.error.exception.WalletNotEmptyException;
import dev.vaullet.ledger.wallet.service.BucketBalance;
import dev.vaullet.ledger.wallet.service.Wallet;
import dev.vaullet.ledger.wallet.service.WalletBalance;
import dev.vaullet.ledger.wallet.service.WalletPage;
import dev.vaullet.ledger.wallet.service.WalletSearch;
import dev.vaullet.ledger.wallet.service.WalletService;
import dev.vaullet.ledger.wallet.service.WalletStatus;
import dev.vaullet.ledger.wallet.service.WalletType;
import java.io.Serial;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Web slice for {@code /v1/wallets}: routing, validation, the real security chain and the real error
 * handling, with {@link WalletService} mocked.
 *
 * <p>Set up exactly like {@code ReservationControllerTest}, for the reasons given there — the
 * platform chain arrives through {@code @ImportAutoConfiguration}, and the 401 and 403 assertions
 * are the point rather than incidental.
 *
 * <p>What is deliberately not tested here: whether a wallet opens, freezes or closes correctly, or
 * what its balance adds up to. Those rules live in the service and in SQL, and are proved against
 * PostgreSQL. These tests prove the HTTP contract around them — and in particular that the three
 * failures this resource adds reach the caller as their own codes rather than as a 500.
 */
@WebMvcTest(WalletController.class)
@ImportAutoConfiguration(PlatformSecurityAutoConfiguration.class)
class WalletControllerTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WALLET_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID CASH_BUCKET_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID GRANT_BUCKET_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final Instant OPENED_AT = Instant.parse("2026-10-13T09:15:00Z");
    private static final Instant CLOSED_AT = Instant.parse("2026-10-20T16:40:00Z");

    private static final String READ = "SCOPE_ledger:read";
    private static final String WRITE = "SCOPE_ledger:write";

    private static final String OPEN_REAL = """
            {"account_id":"11111111-1111-1111-1111-111111111111","type":"REAL"}""";
    private static final String FREEZE = """
            {"status":"FROZEN"}""";

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private WalletService wallets;

    /** Required to assemble the production filter chain; never invoked — see jwt() below. */
    @MockitoBean
    private JwtDecoder jwtDecoder;

    // ---------------------------------------------------------------- security

    @Test
    @DisplayName("rejects an unauthenticated request before it reaches the controller")
    void requiresAuthentication() {
        assertThat(mvc.get().uri("/v1/wallets/{id}", WALLET_ID)).hasStatus(401);

        verifyNoInteractions(wallets);
    }

    @Test
    @DisplayName("opening, freezing and closing need ledger:write — a read token changes nothing")
    void changesNeedTheWriteScope() {
        var refused = assertThat(mvc.post()
                        .uri("/v1/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OPEN_REAL)
                        .with(jwt().authorities(() -> READ)))
                .hasStatus(403);
        refused.bodyJson().extractingPath("$.code").isEqualTo("ACCESS_DENIED");

        assertThat(mvc.patch()
                        .uri("/v1/wallets/{id}", WALLET_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FREEZE)
                        .with(jwt().authorities(() -> READ)))
                .hasStatus(403);
        assertThat(mvc.delete().uri("/v1/wallets/{id}", WALLET_ID).with(jwt().authorities(() -> READ)))
                .hasStatus(403);

        // The important half: authorisation ran before the service did.
        verifyNoInteractions(wallets);
    }

    @Test
    @DisplayName("every read needs ledger:read — a write token sees nothing")
    void readsNeedTheReadScope() {
        assertThat(mvc.get().uri("/v1/wallets/{id}", WALLET_ID).with(jwt().authorities(() -> WRITE)))
                .hasStatus(403);
        assertThat(mvc.get().uri("/v1/wallets").with(jwt().authorities(() -> WRITE)))
                .hasStatus(403);
        assertThat(mvc.get().uri("/v1/wallets/{id}/balance", WALLET_ID).with(jwt().authorities(() -> WRITE)))
                .hasStatus(403);

        verifyNoInteractions(wallets);
    }

    // ---------------------------------------------------------------- open

    @Test
    @DisplayName("opening answers 201 with a Location header and the wallet, in snake_case")
    void opensAWallet() {
        when(wallets.open(ACCOUNT_ID, WalletType.REAL))
                .thenReturn(new WalletService.Opened(wallet(WalletType.REAL, WalletStatus.ACTIVE), true));

        var result = assertThat(mvc.post()
                        .uri("/v1/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OPEN_REAL)
                        .with(jwt().authorities(() -> WRITE)))
                .hasStatus(201);

        result.headers().hasHeaderSatisfying("Location", values -> assertThat(values.getFirst())
                .endsWith("/v1/wallets/" + WALLET_ID));
        result.bodyJson().extractingPath("$.wallet_id").isEqualTo(WALLET_ID.toString());
        result.bodyJson().extractingPath("$.account_id").isEqualTo(ACCOUNT_ID.toString());
        result.bodyJson().extractingPath("$.type").isEqualTo("REAL");
        result.bodyJson().extractingPath("$.status").isEqualTo("ACTIVE");
        result.bodyJson().extractingPath("$.created_at").isEqualTo("2026-10-13T09:15:00Z");
        // Absent rather than null until the wallet closes — the platform's non_null inclusion.
        result.bodyJson().doesNotHavePath("$.closed_at");
    }

    @Test
    @DisplayName("repeating the request returns the existing wallet with 201, not 200")
    void aRepeatAnswersLikeTheFirstCall() {
        when(wallets.open(ACCOUNT_ID, WalletType.REAL))
                .thenReturn(new WalletService.Opened(wallet(WalletType.REAL, WalletStatus.FROZEN), false));

        var result = assertThat(mvc.post()
                        .uri("/v1/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OPEN_REAL)
                        .with(jwt().authorities(() -> WRITE)))
                .hasStatus(201);

        // A retry must not behave differently from the attempt it retries.
        result.headers().hasHeaderSatisfying("Location", values -> assertThat(values.getFirst())
                .endsWith("/v1/wallets/" + WALLET_ID));
        result.bodyJson().extractingPath("$.status").isEqualTo("FROZEN");
    }

    @ParameterizedTest(name = "type {0}")
    @ValueSource(strings = {"\"GOLD\"", "\"real\"", "\"\"", "null"})
    @DisplayName("a type that is not REAL or BONUS is VALIDATION_FAILED naming the field, before the service runs")
    void validatesTheType(String type) {
        var result = assertThat(mvc.post()
                        .uri("/v1/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"account_id":"11111111-1111-1111-1111-111111111111","type":%s}""".formatted(type))
                        .with(jwt().authorities(() -> WRITE)))
                .hasStatus(400)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);

        result.bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        result.bodyJson().extractingPath("$.errors[*].field").asArray().contains("type");
        verify(wallets, never()).open(any(), any());
    }

    @Test
    @DisplayName("an open request without an account is VALIDATION_FAILED")
    void requiresTheAccount() {
        var result = assertThat(mvc.post()
                        .uri("/v1/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"BONUS"}""")
                        .with(jwt().authorities(() -> WRITE)))
                .hasStatus(400);

        result.bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        verify(wallets, never()).open(any(), any());
    }

    @Test
    @DisplayName("reopening a closed wallet's type is 409 WALLET_CLOSED — closing is terminal")
    void aClosedWalletCannotBeReopened() {
        when(wallets.open(ACCOUNT_ID, WalletType.REAL)).thenThrow(new WalletClosedException(WALLET_ID));

        var result = assertThat(mvc.post()
                        .uri("/v1/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OPEN_REAL)
                        .with(jwt().authorities(() -> WRITE)))
                .hasStatus(409)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);

        result.bodyJson().extractingPath("$.code").isEqualTo("WALLET_CLOSED");
        result.bodyJson().extractingPath("$.type").isEqualTo("https://docs.vaullet.dev/errors/wallet-closed");
    }

    // ---------------------------------------------------------------- search

    @Test
    @DisplayName("search passes every filter and the page through, and answers {data, next_cursor}")
    void searchesWallets() {
        var search = new WalletSearch(ACCOUNT_ID, WalletType.BONUS, WalletStatus.FROZEN, 10, "the-previous-cursor");
        when(wallets.search(search))
                .thenReturn(new WalletPage(List.of(wallet(WalletType.BONUS, WalletStatus.FROZEN)), "the-next-cursor"));

        var result = assertThat(mvc.get()
                        .uri("/v1/wallets?account_id={account}&type=BONUS&status=FROZEN&limit=10&cursor=the-previous-cursor",
                                ACCOUNT_ID)
                        .with(jwt().authorities(() -> READ)))
                .hasStatusOk();

        result.bodyJson().extractingPath("$.data[0].wallet_id").isEqualTo(WALLET_ID.toString());
        result.bodyJson().extractingPath("$.data[0].type").isEqualTo("BONUS");
        result.bodyJson().extractingPath("$.next_cursor").isEqualTo("the-next-cursor");
    }

    @Test
    @DisplayName("no parameters is every wallet, 50 at a time; the last page has no next_cursor at all")
    void searchDefaults() {
        when(wallets.search(any())).thenReturn(new WalletPage(List.of(), null));

        var result = assertThat(mvc.get()
                        .uri("/v1/wallets?type=&cursor=")
                        .with(jwt().authorities(() -> READ)))
                .hasStatusOk();

        // An empty value is the same as an absent one, and the limit default is applied here once.
        verify(wallets).search(new WalletSearch(null, null, null, WalletSearch.DEFAULT_LIMIT, null));
        result.bodyJson().extractingPath("$.data").asArray().isEmpty();
        result.bodyJson().doesNotHavePath("$.next_cursor");
    }

    @ParameterizedTest(name = "?{0}")
    @CsvSource({
        "limit=0,           limit",
        "limit=101,         limit",
        "limit=ten,         limit",
        "type=GOLD,         type",
        "status=OPEN,       status",
        "account_id=nope,   account_id"
    })
    @DisplayName("a bad query parameter is VALIDATION_FAILED naming it, before the service runs")
    void validatesTheSearch(String query, String field) {
        var result = assertThat(mvc.get()
                        .uri("/v1/wallets?" + query)
                        .with(jwt().authorities(() -> READ)))
                .hasStatus(400)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);

        // A model attribute, not bare @RequestParams, is what earns these the platform's code.
        result.bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        result.bodyJson().extractingPath("$.errors[*].field").asArray().contains(field);
        verifyNoInteractions(wallets);
    }

    @Test
    @DisplayName("a cursor the service did not issue is VALIDATION_FAILED, not a 500")
    void refusesAForeignCursor() {
        when(wallets.search(any())).thenThrow(new UnrecognisedCursor());

        var result = assertThat(mvc.get()
                        .uri("/v1/wallets?cursor=not-one-of-ours")
                        .with(jwt().authorities(() -> READ)))
                .hasStatus(400);

        result.bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
    }

    // ---------------------------------------------------------------- read

    @Test
    @DisplayName("a closed wallet reads back with closed_at")
    void fetchesAWallet() {
        when(wallets.get(WALLET_ID)).thenReturn(closedBonusWallet());

        var result = assertThat(mvc.get()
                        .uri("/v1/wallets/{id}", WALLET_ID)
                        .with(jwt().authorities(() -> READ)))
                .hasStatusOk();

        result.bodyJson().extractingPath("$.status").isEqualTo("CLOSED");
        result.bodyJson().extractingPath("$.closed_at").isEqualTo("2026-10-20T16:40:00Z");
    }

    @Test
    @DisplayName("an unknown wallet is 404 RESOURCE_NOT_FOUND on every route that names one")
    void anUnknownWalletIsNotFound() {
        var missing = new ResourceNotFoundException("Wallet", WALLET_ID);
        when(wallets.get(WALLET_ID)).thenThrow(missing);
        when(wallets.balance(WALLET_ID)).thenThrow(missing);
        when(wallets.changeStatus(WALLET_ID, WalletStatus.FROZEN)).thenThrow(missing);
        doThrow(missing).when(wallets).close(WALLET_ID);

        assertThat(mvc.get().uri("/v1/wallets/{id}", WALLET_ID).with(jwt().authorities(() -> READ)))
                .hasStatus(404)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(mvc.get().uri("/v1/wallets/{id}/balance", WALLET_ID).with(jwt().authorities(() -> READ)))
                .hasStatus(404);
        assertThat(mvc.patch()
                        .uri("/v1/wallets/{id}", WALLET_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FREEZE)
                        .with(jwt().authorities(() -> WRITE)))
                .hasStatus(404);
        assertThat(mvc.delete().uri("/v1/wallets/{id}", WALLET_ID).with(jwt().authorities(() -> WRITE)))
                .hasStatus(404);
    }

    // ---------------------------------------------------------------- freeze

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"FROZEN", "ACTIVE"})
    @DisplayName("PATCH freezes and unfreezes, and answers with the wallet")
    void changesTheStatus(String status) {
        var target = WalletStatus.valueOf(status);
        when(wallets.changeStatus(WALLET_ID, target)).thenReturn(wallet(WalletType.REAL, target));

        assertThat(mvc.patch()
                        .uri("/v1/wallets/{id}", WALLET_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"%s"}""".formatted(status))
                        .with(jwt().authorities(() -> WRITE)))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo(status);

        verify(wallets).changeStatus(WALLET_ID, target);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"{\"status\":\"CLOSED\"}", "{\"status\":\"PAUSED\"}", "{\"status\":\"frozen\"}", "{}"})
    @DisplayName("CLOSED, an unknown status and a missing one are all VALIDATION_FAILED — closing is DELETE")
    void patchCannotClose(String body) {
        var result = assertThat(mvc.patch()
                        .uri("/v1/wallets/{id}", WALLET_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(jwt().authorities(() -> WRITE)))
                .hasStatus(400);

        result.bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        result.bodyJson().extractingPath("$.errors[*].field").asArray().contains("status");
        // The service would answer CLOSED with an IllegalArgumentException, which is a 500.
        verifyNoInteractions(wallets);
    }

    @Test
    @DisplayName("freezing a closed wallet is 409 WALLET_CLOSED")
    void aClosedWalletCannotBeFrozen() {
        when(wallets.changeStatus(WALLET_ID, WalletStatus.FROZEN)).thenThrow(new WalletClosedException(WALLET_ID));

        assertThat(mvc.patch()
                        .uri("/v1/wallets/{id}", WALLET_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FREEZE)
                        .with(jwt().authorities(() -> WRITE)))
                .hasStatus(409)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("WALLET_CLOSED");
    }

    // ---------------------------------------------------------------- close

    @Test
    @DisplayName("closing answers 204 with no body")
    void closesAWallet() {
        assertThat(mvc.delete().uri("/v1/wallets/{id}", WALLET_ID).with(jwt().authorities(() -> WRITE)))
                .hasStatus(204)
                .body()
                .isEmpty();

        verify(wallets).close(WALLET_ID);
    }

    @Test
    @DisplayName("closing a wallet that still holds money is 409 WALLET_NOT_EMPTY")
    void aWalletWithMoneyCannotBeClosed() {
        doThrow(new WalletNotEmptyException(WALLET_ID, new BigDecimal("100.0000"), BigDecimal.ZERO, BigDecimal.ZERO))
                .when(wallets)
                .close(WALLET_ID);

        var result = assertThat(mvc.delete()
                        .uri("/v1/wallets/{id}", WALLET_ID)
                        .with(jwt().authorities(() -> WRITE)))
                .hasStatus(409)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);

        result.bodyJson().extractingPath("$.code").isEqualTo("WALLET_NOT_EMPTY");
        result.bodyJson().extractingPath("$.type").isEqualTo("https://docs.vaullet.dev/errors/wallet-not-empty");
    }

    // ---------------------------------------------------------------- balance

    @Test
    @DisplayName("a wallet balance carries the currency, money as decimal strings, and its buckets")
    void readsAWalletBalance() {
        when(wallets.balance(WALLET_ID)).thenReturn(new WalletBalance(
                WALLET_ID,
                ACCOUNT_ID,
                WalletType.REAL,
                WalletStatus.ACTIVE,
                "EUR",
                new BigDecimal("100.0000"),
                new BigDecimal("60.0000"),
                new BigDecimal("40.0000"),
                new BigDecimal("40.0000"),
                new BigDecimal("0.0000"),
                List.of(new BucketBalance(
                        CASH_BUCKET_ID,
                        "CASH",
                        new BigDecimal("100.0000"),
                        new BigDecimal("60.0000"),
                        new BigDecimal("40.0000"),
                        true,
                        new BigDecimal("0.0000"),
                        null))));

        var result = assertThat(mvc.get()
                        .uri("/v1/wallets/{id}/balance", WALLET_ID)
                        .with(jwt().authorities(() -> READ)))
                .hasStatusOk();

        var json = result.bodyJson();
        json.extractingPath("$.wallet_id").isEqualTo(WALLET_ID.toString());
        json.extractingPath("$.currency").isEqualTo("EUR");
        // A JSON number here would be a rounding defect waiting for a client with binary floats.
        json.extractingPath("$.posted").isEqualTo("100.0000");
        json.extractingPath("$.available").isEqualTo("40.0000");
        json.extractingPath("$.withdrawable").isEqualTo("40.0000");
        json.extractingPath("$.debt").isEqualTo("0.0000");
        json.extractingPath("$.buckets[0].bucket_id").isEqualTo(CASH_BUCKET_ID.toString());
        json.extractingPath("$.buckets[0].type").isEqualTo("CASH");
        json.extractingPath("$.buckets[0].held").isEqualTo("60.0000");
        json.extractingPath("$.buckets[0].withdrawable").isEqualTo(true);
        json.extractingPath("$.buckets[0].wagering_remaining").isEqualTo("0.0000");
        json.doesNotHavePath("$.buckets[0].expires_at");
    }

    @Test
    @DisplayName("a frozen BONUS wallet shows its money as posted but not available, and each grant's terms")
    void readsAFrozenBonusWallet() {
        when(wallets.balance(WALLET_ID)).thenReturn(new WalletBalance(
                WALLET_ID,
                ACCOUNT_ID,
                WalletType.BONUS,
                WalletStatus.FROZEN,
                "EUR",
                new BigDecimal("50.0000"),
                new BigDecimal("0.0000"),
                new BigDecimal("0.0000"),
                new BigDecimal("0.0000"),
                new BigDecimal("0.0000"),
                List.of(new BucketBalance(
                        GRANT_BUCKET_ID,
                        "BONUS",
                        new BigDecimal("50.0000"),
                        new BigDecimal("0.0000"),
                        new BigDecimal("50.0000"),
                        false,
                        new BigDecimal("250.0000"),
                        Instant.parse("2026-11-12T23:59:59Z")))));

        var json = assertThat(mvc.get()
                        .uri("/v1/wallets/{id}/balance", WALLET_ID)
                        .with(jwt().authorities(() -> READ)))
                .hasStatusOk()
                .bodyJson();

        // The service decides the figures; the wire must not lose the difference between them.
        json.extractingPath("$.status").isEqualTo("FROZEN");
        json.extractingPath("$.posted").isEqualTo("50.0000");
        json.extractingPath("$.available").isEqualTo("0.0000");
        json.extractingPath("$.buckets[0].available").isEqualTo("50.0000");
        json.extractingPath("$.buckets[0].withdrawable").isEqualTo(false);
        json.extractingPath("$.buckets[0].wagering_remaining").isEqualTo("250.0000");
        json.extractingPath("$.buckets[0].expires_at").isEqualTo("2026-11-12T23:59:59Z");
    }

    // ---------------------------------------------------------------- fixtures

    private static Wallet wallet(WalletType type, WalletStatus status) {
        return new Wallet(WALLET_ID, ACCOUNT_ID, type, status, OPENED_AT, OPENED_AT, null);
    }

    private static Wallet closedBonusWallet() {
        return new Wallet(WALLET_ID, ACCOUNT_ID, WalletType.BONUS, WalletStatus.CLOSED, OPENED_AT, CLOSED_AT, CLOSED_AT);
    }

    /** What {@code WalletService.search} throws for a cursor it did not issue, per its contract. */
    private static final class UnrecognisedCursor extends ApplicationException {

        @Serial
        private static final long serialVersionUID = 1L;

        UnrecognisedCursor() {
            super(CommonErrorType.VALIDATION_FAILED, "The cursor was not issued by this service");
        }
    }
}
