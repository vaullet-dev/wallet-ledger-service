package dev.vaullet.ledger.wallet.service;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import dev.vaullet.common.error.ApplicationException;
import dev.vaullet.common.error.CommonErrorType;
import dev.vaullet.common.error.exception.ResourceNotFoundException;
import dev.vaullet.common.test.IntegrationTest;
import dev.vaullet.ledger.common.error.LedgerErrorType;
import dev.vaullet.ledger.common.error.exception.WalletClosedException;
import dev.vaullet.ledger.common.error.exception.WalletNotEmptyException;
import dev.vaullet.ledger.reservation.service.LedgerService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Wallets against a real PostgreSQL: the lifecycle rules, the balance rules, and that the reserve
 * path honours a freeze.
 *
 * <p>Money is booked into buckets by SQL here, as in {@code OverdraftIT}: the credit path arrives
 * with a later issue, and these tests are about what wallets do with money, not how it arrives.
 */
@IntegrationTest
class WalletServiceIT {

    @Autowired WalletService wallets;
    @Autowired LedgerService ledger;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired MockMvcTester mvc;

    UUID account;

    @BeforeEach
    void reset() {
        // TRUNCATE, not DELETE: see OverdraftIT — the journal's append-only rule blocks a DELETE.
        jdbc.update("""
                TRUNCATE ledger_entries, reservation_allocations, reservations,
                         balance_buckets, wallets, account_balances, ledger_config
                """);
        jdbc.update("INSERT INTO ledger_config (currency, minor_units) VALUES ('EUR', 2)");
        account = UUID.randomUUID();
    }

    // ---------------------------------------------------------------- opening

    @Test
    @DisplayName("opening a REAL wallet opens the account in the ledger and gives the wallet its CASH bucket")
    void openingRealOpensTheAccountWithCash() {
        WalletService.Opened opened = wallets.open(account, WalletType.REAL);

        assertThat(opened.created()).isTrue();
        assertThat(opened.wallet().accountId()).isEqualTo(account);
        assertThat(opened.wallet().type()).isEqualTo(WalletType.REAL);
        assertThat(opened.wallet().status()).isEqualTo(WalletStatus.ACTIVE);
        assertThat(opened.wallet().closedAt()).isNull();
        assertThat(ledger.balance(account).posted()).isEqualByComparingTo("0");

        assertThat(wallets.balance(opened.wallet().walletId()).buckets()).singleElement().satisfies(cash -> {
            assertThat(cash.bucketType()).isEqualTo("CASH");
            assertThat(cash.withdrawable()).isTrue();
            assertThat(cash.posted()).isEqualByComparingTo("0");
            assertThat(cash.expiresAt()).isNull();
        });
        // Last in the spend order, behind every grant.
        assertThat(jdbc.queryForObject("SELECT spend_priority FROM balance_buckets WHERE account_id = ?",
                        Integer.class, account))
                .isEqualTo(WalletService.CASH_SPEND_PRIORITY);
    }

    @Test
    @DisplayName("a BONUS wallet starts without buckets: grants add them, one per grant")
    void openingBonusCreatesNoBucket() {
        WalletService.Opened opened = wallets.open(account, WalletType.BONUS);

        assertThat(opened.created()).isTrue();
        assertThat(wallets.balance(opened.wallet().walletId()).buckets()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM balance_buckets", Integer.class)).isZero();
    }

    @Test
    @DisplayName("opening again returns the wallet already there, frozen or not, and creates nothing")
    void openIsIdempotent() {
        WalletService.Opened first = wallets.open(account, WalletType.REAL);
        WalletService.Opened again = wallets.open(account, WalletType.REAL);

        assertThat(again.created()).isFalse();
        assertThat(again.wallet()).isEqualTo(first.wallet());

        Wallet frozen = wallets.changeStatus(first.wallet().walletId(), WalletStatus.FROZEN);
        assertThat(wallets.open(account, WalletType.REAL)).isEqualTo(new WalletService.Opened(frozen, false));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM wallets", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM balance_buckets", Integer.class)).isOne();
    }

    @Test
    @DisplayName("an account has one wallet of each type, and the schema refuses a second")
    void oneWalletPerType() {
        Wallet real = wallets.open(account, WalletType.REAL).wallet();
        Wallet bonus = wallets.open(account, WalletType.BONUS).wallet();

        assertThat(real.walletId()).isNotEqualTo(bonus.walletId());
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO wallets (wallet_id, account_id, wallet_type) VALUES (?, ?, 'REAL')",
                        UUID.randomUUID(), account))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("wallets_one_per_type");
    }

    @Test
    @DisplayName("eight concurrent opens of one type all succeed with one wallet, for a new account and a known one")
    void concurrentOpensMakeOneWallet() throws Exception {
        // New to the ledger: the openers race to insert the account row as well as the wallet.
        List<WalletService.Opened> real = concurrently(8, () -> wallets.open(account, WalletType.REAL));
        // Known by now: they queue on the account row lock, and the conflict sends all but one home.
        List<WalletService.Opened> bonus = concurrently(8, () -> wallets.open(account, WalletType.BONUS));

        for (List<WalletService.Opened> results : List.of(real, bonus)) {
            assertThat(results).extracting(opened -> opened.wallet().walletId())
                    .containsOnly(results.getFirst().wallet().walletId());
            assertThat(results).filteredOn(WalletService.Opened::created).hasSize(1);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wallets", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM balance_buckets", Integer.class)).isOne();
    }

    @Test
    @DisplayName("concurrent opens of both types for a new account make one wallet of each")
    void concurrentOpensOfBothTypes() throws Exception {
        List<WalletService.Opened> results = concurrently(8, () -> wallets.open(account, WalletType.REAL),
                () -> wallets.open(account, WalletType.BONUS));

        assertThat(results).filteredOn(WalletService.Opened::created)
                .extracting(opened -> opened.wallet().type())
                .containsExactlyInAnyOrder(WalletType.REAL, WalletType.BONUS);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wallets", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_balances", Integer.class)).isOne();
    }

    @Test
    @DisplayName("a closed wallet's type cannot be opened again: closing is terminal")
    void reopeningAClosedTypeIsRefused() {
        Wallet real = wallets.open(account, WalletType.REAL).wallet();
        wallets.close(real.walletId());

        assertThatThrownBy(() -> wallets.open(account, WalletType.REAL))
                .isInstanceOfSatisfying(WalletClosedException.class,
                        e -> assertThat(e.errorType()).isEqualTo(LedgerErrorType.WALLET_CLOSED))
                .hasMessageContaining(real.walletId().toString());
        assertThat(wallets.open(account, WalletType.BONUS).created()).isTrue();
    }

    @Test
    @DisplayName("opening, freezing and closing queue on the account row, and give up at statement_timeout")
    void walletWritesTakeTheAccountLockFirst() throws Exception {
        Wallet real = wallets.open(account, WalletType.REAL).wallet();

        try (Connection settle = dataSource.getConnection()) {
            // Another path mid-transaction on this account — a reserve, a settle, a grant — holding
            // ADR-004's anchor. Every wallet write waits behind it, then gives up after the 1s
            // statement_timeout instead of parking a thread: ACCOUNT_BUSY at the API.
            settle.setAutoCommit(false);
            try (PreparedStatement lock =
                    settle.prepareStatement("SELECT 1 FROM account_balances WHERE account_id = ? FOR UPDATE")) {
                lock.setObject(1, account);
                lock.executeQuery();
            }
            assertThatThrownBy(() -> wallets.open(account, WalletType.BONUS)).isInstanceOf(QueryTimeoutException.class);
            assertThatThrownBy(() -> wallets.changeStatus(real.walletId(), WalletStatus.FROZEN))
                    .isInstanceOf(QueryTimeoutException.class);
            assertThatThrownBy(() -> wallets.close(real.walletId())).isInstanceOf(QueryTimeoutException.class);
            settle.rollback();
        }

        assertThat(wallets.changeStatus(real.walletId(), WalletStatus.FROZEN).status()).isEqualTo(WalletStatus.FROZEN);
        assertThat(wallets.balancesOf(account)).hasSize(1);
    }

    // ---------------------------------------------------------------- freezing

    @Test
    @DisplayName("freeze and unfreeze move the status and updated_at; repeating a status changes nothing")
    void freezeAndUnfreeze() {
        Wallet opened = wallets.open(account, WalletType.REAL).wallet();

        Wallet frozen = wallets.changeStatus(opened.walletId(), WalletStatus.FROZEN);
        assertThat(frozen.status()).isEqualTo(WalletStatus.FROZEN);
        assertThat(frozen.updatedAt()).isAfter(opened.updatedAt());
        assertThat(frozen.createdAt()).isEqualTo(opened.createdAt());

        assertThat(wallets.changeStatus(opened.walletId(), WalletStatus.FROZEN)).isEqualTo(frozen);
        assertThat(wallets.get(opened.walletId())).isEqualTo(frozen);

        Wallet active = wallets.changeStatus(opened.walletId(), WalletStatus.ACTIVE);
        assertThat(active.status()).isEqualTo(WalletStatus.ACTIVE);
        assertThat(active.updatedAt()).isAfter(frozen.updatedAt());
        assertThat(active.closedAt()).isNull();
    }

    @Test
    @DisplayName("a closed wallet can be neither frozen nor unfrozen, and CLOSED is not a status to set")
    void closedIsTerminal() {
        Wallet bonus = wallets.open(account, WalletType.BONUS).wallet();

        assertThatThrownBy(() -> wallets.changeStatus(bonus.walletId(), WalletStatus.CLOSED))
                .isInstanceOf(IllegalArgumentException.class);

        wallets.close(bonus.walletId());
        assertThatThrownBy(() -> wallets.changeStatus(bonus.walletId(), WalletStatus.FROZEN))
                .isInstanceOf(WalletClosedException.class);
        assertThatThrownBy(() -> wallets.changeStatus(bonus.walletId(), WalletStatus.ACTIVE))
                .isInstanceOf(WalletClosedException.class);
    }

    @Test
    @DisplayName("an unknown wallet is RESOURCE_NOT_FOUND; an unknown account simply has no wallets")
    void unknownWallets() {
        UUID nobody = UUID.randomUUID();

        for (Runnable call : List.<Runnable>of(
                () -> wallets.get(nobody),
                () -> wallets.balance(nobody),
                () -> wallets.changeStatus(nobody, WalletStatus.FROZEN),
                () -> wallets.close(nobody))) {
            assertThatThrownBy(call::run)
                    .isInstanceOfSatisfying(ResourceNotFoundException.class,
                            e -> assertThat(e.errorType()).isEqualTo(CommonErrorType.RESOURCE_NOT_FOUND));
        }
        assertThat(wallets.balancesOf(nobody)).isEmpty();
    }

    // ---------------------------------------------------------------- closing

    @Test
    @DisplayName("a wallet with posted money, a hold or a debt cannot be closed")
    void onlyAnEmptyWalletCloses() {
        Wallet real = wallets.open(account, WalletType.REAL).wallet();

        fundCash(account, "100.0000");
        assertThatThrownBy(() -> wallets.close(real.walletId()))
                .isInstanceOfSatisfying(WalletNotEmptyException.class,
                        e -> assertThat(e.errorType()).isEqualTo(LedgerErrorType.WALLET_NOT_EMPTY))
                .hasMessageContaining("posted 100.0000, held 0.0000 and debt 0.0000");

        ledger.reserve(account, new BigDecimal("60.0000"), "hold-60", null);
        assertThatThrownBy(() -> wallets.close(real.walletId()))
                .isInstanceOf(WalletNotEmptyException.class)
                .hasMessageContaining("held 60.0000");

        Wallet owing = wallets.open(UUID.randomUUID(), WalletType.REAL).wallet();
        debt(owing.accountId(), "25.0000");
        assertThatThrownBy(() -> wallets.close(owing.walletId()))
                .isInstanceOf(WalletNotEmptyException.class)
                .hasMessageContaining("posted 0.0000, held 0.0000 and debt 25.0000");

        assertThat(wallets.get(real.walletId()).status()).isEqualTo(WalletStatus.ACTIVE);
        assertThat(wallets.get(owing.walletId()).status()).isEqualTo(WalletStatus.ACTIVE);
    }

    @Test
    @DisplayName("an empty wallet closes, frozen or not, once: closing it again changes nothing")
    void emptyWalletClosesOnce() {
        Wallet bonus = wallets.open(account, WalletType.BONUS).wallet();
        wallets.changeStatus(bonus.walletId(), WalletStatus.FROZEN);

        wallets.close(bonus.walletId());
        Wallet closed = wallets.get(bonus.walletId());
        assertThat(closed.status()).isEqualTo(WalletStatus.CLOSED);
        assertThat(closed.closedAt()).isNotNull();
        assertThat(closed.updatedAt()).isEqualTo(closed.closedAt());

        wallets.close(bonus.walletId());
        assertThat(wallets.get(bonus.walletId())).isEqualTo(closed);
    }

    @Test
    @DisplayName("a wallet whose money has all been spent is empty, and closes with its buckets kept")
    void aSpentWalletCloses() {
        Wallet real = wallets.open(account, WalletType.REAL).wallet();
        fundCash(account, "100.0000");
        LedgerService.Reservation stake = ledger.reserve(account, new BigDecimal("100.0000"), "all-in", null);
        ledger.settle(stake.reservationId(), UUID.randomUUID());

        wallets.close(real.walletId());

        WalletBalance balance = wallets.balance(real.walletId());
        assertThat(balance.status()).isEqualTo(WalletStatus.CLOSED);
        // The journal points at the CASH bucket, so the bucket outlives the wallet's useful life.
        assertThat(balance.buckets()).singleElement()
                .satisfies(cash -> assertThat(cash.posted()).isEqualByComparingTo("0"));
    }

    // ---------------------------------------------------------------- balances

    @Test
    @DisplayName("a wallet's balance sums its buckets: debt apart, holds held, CASH first, four decimal places")
    void walletBalance() {
        Wallet real = wallets.open(account, WalletType.REAL).wallet();
        fundCash(account, "100.0000");
        debt(account, "25.0000");
        ledger.reserve(account, new BigDecimal("30.0000"), "hold-30", null);

        WalletBalance balance = wallets.balance(real.walletId());

        assertThat(balance.walletId()).isEqualTo(real.walletId());
        assertThat(balance.accountId()).isEqualTo(account);
        assertThat(balance.type()).isEqualTo(WalletType.REAL);
        assertThat(balance.status()).isEqualTo(WalletStatus.ACTIVE);
        assertThat(balance.currency()).isEqualTo("EUR");
        assertThat(balance.posted()).hasToString("100.0000");
        assertThat(balance.held()).hasToString("30.0000");
        assertThat(balance.available()).hasToString("70.0000");
        assertThat(balance.withdrawable()).hasToString("70.0000");
        assertThat(balance.debt()).hasToString("25.0000");
        assertThat(balance.buckets()).extracting(BucketBalance::bucketType).containsExactly("CASH", "DEBT");
        // The DEBT bucket shows what is owed as posted; it funds nothing, so nothing of it is available.
        assertThat(balance.buckets()).extracting(BucketBalance::posted, BucketBalance::available)
                .containsExactly(
                        tuple(new BigDecimal("100.0000"), new BigDecimal("70.0000")),
                        tuple(new BigDecimal("25.0000"), new BigDecimal("0.0000")));
        assertThat(sumOfBuckets(balance)).isEqualByComparingTo(balance.available());
    }

    @Test
    @DisplayName("a frozen wallet keeps its money, but none of it is available or withdrawable")
    void frozenWalletBalance() {
        Wallet real = wallets.open(account, WalletType.REAL).wallet();
        fundCash(account, "100.0000");
        ledger.reserve(account, new BigDecimal("30.0000"), "hold-30", null);

        wallets.changeStatus(real.walletId(), WalletStatus.FROZEN);
        WalletBalance balance = wallets.balance(real.walletId());

        assertThat(balance.posted()).hasToString("100.0000");
        assertThat(balance.held()).hasToString("30.0000");
        assertThat(balance.available()).hasToString("0.0000");
        assertThat(balance.withdrawable()).hasToString("0.0000");
        // "available" means the same on a bucket as on its wallet: the CASH is still posted and held,
        // and none of it can fund a hold.
        assertThat(balance.buckets().getFirst().posted()).hasToString("100.0000");
        assertThat(balance.buckets().getFirst().held()).hasToString("30.0000");
        assertThat(balance.buckets().getFirst().available()).hasToString("0.0000");
    }

    @Test
    @DisplayName("promotional money spends but does not withdraw; buckets come in spend order")
    void bonusWalletBalance() {
        Wallet bonus = wallets.open(account, WalletType.BONUS).wallet();
        UUID later = grant(account, "40.0000", 20, false, null);
        UUID sooner = grant(account, "60.0000", 10, false, null);
        UUID lapsed = grant(account, "5.0000", 30, true, OffsetDateTime.now().minusHours(1));

        WalletBalance balance = wallets.balance(bonus.walletId());

        // The lapsed grant stays posted until the sweeper forfeits it, but the reserve path will not
        // spend it, so it is neither available nor, although it is marked withdrawable, withdrawable.
        assertThat(balance.posted()).hasToString("105.0000");
        assertThat(balance.available()).hasToString("100.0000");
        assertThat(balance.withdrawable()).hasToString("0.0000");
        assertThat(balance.buckets()).extracting(BucketBalance::bucketId).containsExactly(sooner, later, lapsed);
        assertThat(balance.buckets()).extracting(BucketBalance::available)
                .containsExactly(new BigDecimal("60.0000"), new BigDecimal("40.0000"), new BigDecimal("0.0000"));
        assertThat(balance.buckets().getFirst().wageringRemaining()).isEqualByComparingTo("600");
    }

    @Test
    @DisplayName("a lapsed grant the sweeper has not reached is posted but not available, and a hold agrees")
    void aLapsedGrantIsNotAvailable() {
        wallets.open(account, WalletType.REAL);
        Wallet bonus = wallets.open(account, WalletType.BONUS).wallet();
        grant(account, "50.0000", 10, false, OffsetDateTime.now().minusHours(1));

        LedgerService.Balance totals = ledger.balance(account);
        assertThat(totals.posted()).hasToString("50.0000");
        assertThat(totals.available()).hasToString("0.0000");
        assertThat(wallets.balance(bonus.walletId()).available()).hasToString("0.0000");
        assertThat(sum(wallets.balancesOf(account), WalletBalance::available)).isEqualByComparingTo(totals.available());

        // The figure did not promise anything the reserve path then refuses.
        assertThatThrownBy(() -> ledger.reserve(account, new BigDecimal("10.0000"), "hold-lapsed", null))
                .isInstanceOf(LedgerService.InsufficientFunds.class);
    }

    @Test
    @DisplayName("the account's available and withdrawable count ACTIVE wallets only: the sum of its wallets")
    void accountBalanceCountsActiveWalletsOnly() {
        Wallet real = wallets.open(account, WalletType.REAL).wallet();
        Wallet bonus = wallets.open(account, WalletType.BONUS).wallet();
        fundCash(account, "100.0000");
        grant(account, "60.0000", 10, false, null);

        assertThat(ledger.balance(account)).isEqualTo(new LedgerService.Balance(
                new BigDecimal("160.0000"), new BigDecimal("0.0000"), new BigDecimal("160.0000"),
                new BigDecimal("100.0000"), new BigDecimal("0.0000")));

        wallets.changeStatus(real.walletId(), WalletStatus.FROZEN);
        LedgerService.Balance frozenReal = ledger.balance(account);
        assertThat(frozenReal.posted()).hasToString("160.0000");
        assertThat(frozenReal.available()).hasToString("60.0000");
        assertThat(frozenReal.withdrawable()).hasToString("0.0000");
        assertThat(sum(wallets.balancesOf(account), WalletBalance::available)).isEqualByComparingTo(frozenReal.available());
        assertThat(sum(wallets.balancesOf(account), WalletBalance::withdrawable))
                .isEqualByComparingTo(frozenReal.withdrawable());

        wallets.changeStatus(bonus.walletId(), WalletStatus.FROZEN);
        assertThat(ledger.balance(account).available()).hasToString("0.0000");

        wallets.changeStatus(real.walletId(), WalletStatus.ACTIVE);
        wallets.changeStatus(bonus.walletId(), WalletStatus.ACTIVE);
        assertThat(ledger.balance(account).available()).hasToString("160.0000");
    }

    @Test
    @DisplayName("balancesOf lists every wallet of the account, REAL first, closed ones included")
    void balancesOfListsRealFirst() {
        Wallet bonus = wallets.open(account, WalletType.BONUS).wallet();
        Wallet real = wallets.open(account, WalletType.REAL).wallet();
        wallets.close(bonus.walletId());
        wallets.open(UUID.randomUUID(), WalletType.REAL);

        assertThat(wallets.balancesOf(account))
                .extracting(WalletBalance::walletId, WalletBalance::status)
                .containsExactly(
                        tuple(real.walletId(), WalletStatus.ACTIVE),
                        tuple(bonus.walletId(), WalletStatus.CLOSED));
    }

    // ---------------------------------------------------------------- spending respects status

    @Test
    @DisplayName("a frozen REAL wallet with 100 posted cannot fund a hold of 10: 409 INSUFFICIENT_FUNDS")
    void aFrozenWalletCannotBeSpent() {
        Wallet real = wallets.open(account, WalletType.REAL).wallet();
        fundCash(account, "100.0000");
        wallets.changeStatus(real.walletId(), WalletStatus.FROZEN);

        assertThatThrownBy(() -> ledger.reserve(account, new BigDecimal("10.0000"), "frozen", null))
                .isInstanceOf(LedgerService.InsufficientFunds.class);

        // As the caller sees it: the account row says 100 is there, the allocation finds none of it.
        assertThat(mvc.post()
                        .uri("/v1/reservations")
                        .header("Idempotency-Key", "frozen-over-http")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"account_id":"%s","amount":"10.00"}""".formatted(account))
                        .with(jwt().authorities(() -> "SCOPE_ledger:write")))
                .hasStatus(409)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservations", Integer.class)).isZero();

        wallets.changeStatus(real.walletId(), WalletStatus.ACTIVE);
        assertThat(ledger.reserve(account, new BigDecimal("10.0000"), "thawed", null).state()).isEqualTo("HELD");
    }

    @Test
    @DisplayName("a hold allocates around a frozen BONUS wallet and draws on cash instead")
    void allocationSkipsFrozenWallets() {
        wallets.open(account, WalletType.REAL);
        Wallet bonus = wallets.open(account, WalletType.BONUS).wallet();
        fundCash(account, "100.0000");
        grant(account, "60.0000", 10, false, null);
        wallets.changeStatus(bonus.walletId(), WalletStatus.FROZEN);

        LedgerService.Reservation hold = ledger.reserve(account, new BigDecimal("50.0000"), "around-the-bonus", null);

        assertThat(hold.allocations()).singleElement().satisfies(a -> {
            assertThat(a.bucketType()).isEqualTo("CASH");
            assertThat(a.amount()).isEqualByComparingTo("50");
        });
    }

    // ---------------------------------------------------------------- search

    @Test
    @DisplayName("search filters by account, type and status, combined with AND, oldest first")
    void searchFilters() {
        Wallet aReal = wallets.open(account, WalletType.REAL).wallet();
        Wallet aBonus = wallets.open(account, WalletType.BONUS).wallet();
        UUID other = UUID.randomUUID();
        Wallet bReal = wallets.open(other, WalletType.REAL).wallet();
        Wallet bBonus = wallets.open(other, WalletType.BONUS).wallet();
        wallets.changeStatus(aBonus.walletId(), WalletStatus.FROZEN);
        wallets.close(bBonus.walletId());

        assertThat(ids(search(null, null, null))).containsExactlyElementsOf(oldestFirst(aReal, aBonus, bReal, bBonus));
        assertThat(ids(search(account, null, null))).containsExactlyElementsOf(oldestFirst(aReal, aBonus));
        assertThat(ids(search(null, WalletType.REAL, null))).containsExactlyElementsOf(oldestFirst(aReal, bReal));
        assertThat(ids(search(null, null, WalletStatus.FROZEN))).containsExactly(aBonus.walletId());
        assertThat(ids(search(account, WalletType.REAL, null))).containsExactly(aReal.walletId());
        assertThat(ids(search(other, null, WalletStatus.ACTIVE))).containsExactly(bReal.walletId());
        assertThat(ids(search(null, WalletType.BONUS, WalletStatus.CLOSED))).containsExactly(bBonus.walletId());
        assertThat(search(UUID.randomUUID(), null, null)).isEqualTo(new WalletPage(List.of(), null));
    }

    @Test
    @DisplayName("search pages through every match oldest first, with no gaps, no repeats and a null last cursor")
    void searchPages() {
        List<Wallet> real = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            UUID owner = UUID.randomUUID();
            real.add(wallets.open(owner, WalletType.REAL).wallet());
            wallets.open(owner, WalletType.BONUS);
        }

        List<WalletPage> pages = pages(new WalletSearch(null, WalletType.REAL, null, 3, null));

        assertThat(pages).extracting(page -> page.data().size()).containsExactly(3, 3, 1);
        assertThat(pages.getLast().nextCursor()).isNull();
        assertThat(pages.stream().flatMap(page -> ids(page).stream()).toList())
                .containsExactlyElementsOf(oldestFirst(real.toArray(Wallet[]::new)));

        // A page that ends exactly on the last match is the last page: no cursor to an empty one.
        assertThat(pages(new WalletSearch(null, WalletType.REAL, null, 7, null))).singleElement()
                .satisfies(page -> assertThat(page.nextCursor()).isNull());
    }

    @Test
    @DisplayName("wallets sharing a created_at are ordered by wallet_id, and the cursor keeps its place to the microsecond")
    void searchKeysetIsExact() {
        // One transaction's wallets share now(), as the V2 backfill's do. The last one is a single
        // microsecond younger: a cursor that kept milliseconds would skip or repeat it.
        String sameInstant = "2026-10-13T10:00:00.123456Z";
        List<UUID> tied = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tied.add(walletCreatedAt(sameInstant));
        }
        UUID younger = walletCreatedAt("2026-10-13T10:00:00.123457Z");
        tied.sort(POSTGRES_UUID_ORDER);

        List<WalletPage> pages = pages(new WalletSearch(null, null, null, 1, null));

        assertThat(pages.stream().flatMap(page -> ids(page).stream()).toList())
                .containsExactly(tied.get(0), tied.get(1), tied.get(2), younger);
    }

    @Test
    @DisplayName("a cursor this service did not issue, or a limit out of range, is VALIDATION_FAILED")
    void searchRefusesWhatItCannotRun() {
        UUID id = UUID.randomUUID();
        List<String> refused = new ArrayList<>(List.of(
                "not base64url!",
                "",
                base64url("v2:1791885600123456:" + id),
                base64url("v1:yesterday:" + id),
                base64url("v1:1791885600123456:not-a-uuid"),
                base64url("v1:-1:" + id),
                base64url("v1:1791885600123456:" + id + ":extra")));

        for (String cursor : refused) {
            assertThatThrownBy(() -> wallets.search(new WalletSearch(null, null, null, 10, cursor)))
                    .as(cursor)
                    .isInstanceOfSatisfying(ApplicationException.class,
                            e -> assertThat(e.errorType()).isEqualTo(CommonErrorType.VALIDATION_FAILED));
        }
        for (int limit : new int[] {0, -1, WalletSearch.MAX_LIMIT + 1}) {
            assertThatThrownBy(() -> wallets.search(new WalletSearch(null, null, null, limit, null)))
                    .isInstanceOfSatisfying(ApplicationException.class,
                            e -> assertThat(e.errorType()).isEqualTo(CommonErrorType.VALIDATION_FAILED));
        }
    }

    @Test
    @DisplayName("the currency is ledger_config's")
    void currencyComesFromLedgerConfig() {
        assertThat(wallets.currency()).isEqualTo("EUR");
    }

    // ---------------------------------------------------------------- fixtures

    private WalletPage search(@Nullable UUID accountId, @Nullable WalletType type, @Nullable WalletStatus status) {
        return wallets.search(new WalletSearch(accountId, type, status, WalletSearch.DEFAULT_LIMIT, null));
    }

    /** Follows next cursors to the end; fails rather than loop if a cursor never runs out. */
    private List<WalletPage> pages(WalletSearch first) {
        List<WalletPage> pages = new ArrayList<>();
        WalletSearch search = first;
        while (pages.size() < 50) {
            WalletPage page = wallets.search(search);
            pages.add(page);
            if (page.nextCursor() == null) {
                return pages;
            }
            search = new WalletSearch(first.accountId(), first.type(), first.status(), first.limit(), page.nextCursor());
        }
        throw new AssertionError("the cursor never ran out");
    }

    private static List<UUID> ids(WalletPage page) {
        return page.data().stream().map(Wallet::walletId).toList();
    }

    /**
     * PostgreSQL orders {@code uuid} as unsigned bytes, which is the order of the lowercase text.
     * {@code UUID.compareTo} compares signed longs and disagrees whenever the top bit is set.
     */
    private static final Comparator<UUID> POSTGRES_UUID_ORDER = Comparator.comparing(UUID::toString);

    /** The order the search promises: created_at, then wallet_id. */
    private static List<UUID> oldestFirst(Wallet... wallets) {
        return Arrays.stream(wallets)
                .sorted(Comparator.comparing(Wallet::createdAt)
                        .thenComparing(Wallet::walletId, POSTGRES_UUID_ORDER))
                .map(Wallet::walletId)
                .toList();
    }

    private static BigDecimal sumOfBuckets(WalletBalance balance) {
        return balance.buckets().stream().map(BucketBalance::available).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal sum(List<WalletBalance> balances,
                                  Function<WalletBalance, BigDecimal> figure) {
        return balances.stream().map(figure).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String base64url(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }

    /** Runs the calls on {@code threads} threads, released together, and returns every result. */
    @SafeVarargs
    private static <T> List<T> concurrently(int threads, Callable<T>... calls) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<T>> futures = IntStream.range(0, threads)
                    .mapToObj(i -> pool.submit(() -> {
                        start.await();
                        return calls[i % calls.length].call();
                    }))
                    .toList();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Books money into the account's CASH bucket, keeping the account aggregate in step. */
    private void fundCash(UUID accountId, String amount) {
        BigDecimal money = new BigDecimal(amount);
        jdbc.update("UPDATE balance_buckets SET posted_balance = posted_balance + ? "
                + "WHERE account_id = ? AND bucket_type = 'CASH'", money, accountId);
        jdbc.update("UPDATE account_balances SET posted_balance = posted_balance + ? WHERE account_id = ?",
                money, accountId);
    }

    /** A promotional grant in the account's BONUS wallet, as the rewards modules will make them. */
    private UUID grant(UUID accountId, String amount, int spendPriority, boolean withdrawable,
                       @Nullable OffsetDateTime expiresAt) {
        UUID bucket = UUID.randomUUID();
        BigDecimal money = new BigDecimal(amount);
        jdbc.update("""
                INSERT INTO balance_buckets (bucket_id, account_id, wallet_id, bucket_type, source_module,
                                             grant_id, posted_balance, withdrawable, wagering_remaining,
                                             spend_priority, expires_at)
                VALUES (?, ?, (SELECT wallet_id FROM wallets WHERE account_id = ? AND wallet_type = 'BONUS'),
                        'BONUS', 'bonus', ?, ?, ?, 600.0000, ?, ?)
                """, bucket, accountId, accountId, UUID.randomUUID(), money, withdrawable, spendPriority, expiresAt);
        jdbc.update("UPDATE account_balances SET posted_balance = posted_balance + ? WHERE account_id = ?",
                money, accountId);
        return bucket;
    }

    /** What the user owes (ADR-009): a DEBT bucket in the REAL wallet, and the account's debt_total. */
    private void debt(UUID accountId, String amount) {
        BigDecimal money = new BigDecimal(amount);
        jdbc.update("""
                INSERT INTO balance_buckets (bucket_id, account_id, wallet_id, bucket_type, posted_balance,
                                             withdrawable, spend_priority)
                VALUES (?, ?, (SELECT wallet_id FROM wallets WHERE account_id = ? AND wallet_type = 'REAL'),
                        'DEBT', ?, FALSE, 0)
                """, UUID.randomUUID(), accountId, accountId, money);
        jdbc.update("UPDATE account_balances SET debt_total = debt_total + ? WHERE account_id = ?", money, accountId);
    }

    /** A REAL wallet with a created_at of the test's choosing, for its own new account. */
    private UUID walletCreatedAt(String createdAt) {
        UUID owner = UUID.randomUUID();
        UUID wallet = UUID.randomUUID();
        jdbc.update("INSERT INTO account_balances (account_id) VALUES (?)", owner);
        jdbc.update("INSERT INTO wallets (wallet_id, account_id, wallet_type, created_at, updated_at) "
                + "VALUES (?, ?, 'REAL', ?::timestamptz, ?::timestamptz)", wallet, owner, createdAt, createdAt);
        return wallet;
    }
}
