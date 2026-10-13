package dev.vaullet.ledger.reservation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import dev.vaullet.common.security.PlatformSecurityAutoConfiguration;
import dev.vaullet.ledger.reservation.service.LedgerService;
import dev.vaullet.ledger.wallet.service.BucketBalance;
import dev.vaullet.ledger.wallet.service.WalletBalance;
import dev.vaullet.ledger.wallet.service.WalletService;
import dev.vaullet.ledger.wallet.service.WalletStatus;
import dev.vaullet.ledger.wallet.service.WalletType;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.AbstractJsonContentAssert;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Web slice for {@code GET /v1/accounts/{id}/balance}: the account's totals as before, plus the
 * currency and each wallet's totals.
 *
 * <p>The figures themselves are the services'. What this pins is the shape: that the five totals
 * kept their names and their meaning when the wallets arrived, that the wallets come without their
 * buckets, and that an unknown account is still a 404 rather than an empty balance.
 */
@WebMvcTest(AccountBalanceController.class)
@ImportAutoConfiguration(PlatformSecurityAutoConfiguration.class)
class AccountBalanceControllerTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID REAL_WALLET_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID BONUS_WALLET_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");

    private static final String READ = "SCOPE_ledger:read";
    private static final String WRITE = "SCOPE_ledger:write";

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private LedgerService ledger;

    @MockitoBean
    private WalletService wallets;

    /** Required to assemble the production filter chain; never invoked — see jwt() below. */
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("needs a token, and the token needs ledger:read")
    void requiresTheReadScope() {
        assertThat(mvc.get().uri("/v1/accounts/{id}/balance", ACCOUNT_ID)).hasStatus(401);
        assertThat(mvc.get()
                        .uri("/v1/accounts/{id}/balance", ACCOUNT_ID)
                        .with(jwt().authorities(() -> WRITE)))
                .hasStatus(403);

        verifyNoInteractions(ledger, wallets);
    }

    @Test
    @DisplayName("the account's totals as before, plus the currency and each wallet's totals without buckets")
    void addsTheCurrencyAndTheWallets() {
        // 100 of cash with 60 held, and 50 of bonus in a frozen wallet: posted counts the frozen
        // money, available does not. The services compute that; this test only carries it through.
        when(ledger.balance(ACCOUNT_ID)).thenReturn(new LedgerService.Balance(
                new BigDecimal("150.0000"),
                new BigDecimal("60.0000"),
                new BigDecimal("40.0000"),
                new BigDecimal("40.0000"),
                new BigDecimal("0.0000")));
        when(wallets.currency()).thenReturn("EUR");
        when(wallets.balancesOf(ACCOUNT_ID)).thenReturn(List.of(realWallet(), frozenBonusWallet()));

        AbstractJsonContentAssert<?> json = assertThat(mvc.get()
                        .uri("/v1/accounts/{id}/balance", ACCOUNT_ID)
                        .with(jwt().authorities(() -> READ)))
                .hasStatusOk()
                .bodyJson();

        json.extractingPath("$.currency").isEqualTo("EUR");
        json.extractingPath("$.posted").isEqualTo("150.0000");
        json.extractingPath("$.held").isEqualTo("60.0000");
        json.extractingPath("$.available").isEqualTo("40.0000");
        json.extractingPath("$.withdrawable").isEqualTo("40.0000");
        json.extractingPath("$.debt").isEqualTo("0.0000");

        json.extractingPath("$.wallets[0].wallet_id").isEqualTo(REAL_WALLET_ID.toString());
        json.extractingPath("$.wallets[0].type").isEqualTo("REAL");
        json.extractingPath("$.wallets[0].status").isEqualTo("ACTIVE");
        json.extractingPath("$.wallets[0].posted").isEqualTo("100.0000");
        json.extractingPath("$.wallets[0].available").isEqualTo("40.0000");
        json.extractingPath("$.wallets[1].type").isEqualTo("BONUS");
        json.extractingPath("$.wallets[1].status").isEqualTo("FROZEN");
        json.extractingPath("$.wallets[1].posted").isEqualTo("50.0000");
        json.extractingPath("$.wallets[1].available").isEqualTo("0.0000");

        // A summary: the buckets are one request away, and the account and currency are said once.
        json.doesNotHavePath("$.wallets[0].buckets");
        json.doesNotHavePath("$.wallets[0].account_id");
        json.doesNotHavePath("$.wallets[0].currency");
    }

    @Test
    @DisplayName("an account with no wallets has an empty wallets array, not a missing one")
    void anAccountWithoutWallets() {
        BigDecimal zero = new BigDecimal("0.0000");
        when(ledger.balance(ACCOUNT_ID)).thenReturn(new LedgerService.Balance(zero, zero, zero, zero, zero));
        when(wallets.currency()).thenReturn("EUR");
        when(wallets.balancesOf(ACCOUNT_ID)).thenReturn(List.of());

        assertThat(mvc.get()
                        .uri("/v1/accounts/{id}/balance", ACCOUNT_ID)
                        .with(jwt().authorities(() -> READ)))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.wallets")
                .asArray()
                .isEmpty();
    }

    @Test
    @DisplayName("an account the ledger has never seen is still 404 RESOURCE_NOT_FOUND")
    void anUnknownAccountIsNotFound() {
        // How LedgerService reports it today; LedgerErrorBridge translates it.
        when(ledger.balance(ACCOUNT_ID)).thenThrow(new EmptyResultDataAccessException(1));

        assertThat(mvc.get()
                        .uri("/v1/accounts/{id}/balance", ACCOUNT_ID)
                        .with(jwt().authorities(() -> READ)))
                .hasStatus(404)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("RESOURCE_NOT_FOUND");

        // balancesOf answers an unknown account with an empty list, so asking it first would have
        // turned a 404 into a balance of nothing.
        verify(wallets, never()).balancesOf(any());
    }

    private static WalletBalance realWallet() {
        return new WalletBalance(
                REAL_WALLET_ID,
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
                        UUID.fromString("33333333-3333-3333-3333-333333333333"),
                        "CASH",
                        new BigDecimal("100.0000"),
                        new BigDecimal("60.0000"),
                        new BigDecimal("40.0000"),
                        true,
                        new BigDecimal("0.0000"),
                        null)));
    }

    private static WalletBalance frozenBonusWallet() {
        BigDecimal zero = new BigDecimal("0.0000");
        return new WalletBalance(
                BONUS_WALLET_ID,
                ACCOUNT_ID,
                WalletType.BONUS,
                WalletStatus.FROZEN,
                "EUR",
                new BigDecimal("50.0000"),
                zero,
                zero,
                zero,
                zero,
                List.of(new BucketBalance(
                        UUID.fromString("55555555-5555-5555-5555-555555555555"),
                        "BONUS",
                        new BigDecimal("50.0000"),
                        zero,
                        new BigDecimal("50.0000"),
                        false,
                        new BigDecimal("250.0000"),
                        null)));
    }
}
