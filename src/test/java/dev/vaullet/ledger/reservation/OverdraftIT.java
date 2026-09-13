package dev.vaullet.ledger.reservation;

import dev.vaullet.common.test.IntegrationTest;
import dev.vaullet.ledger.reservation.dao.LedgerRepository;
import dev.vaullet.ledger.reservation.service.LedgerService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * The failure ADR-001 could not prevent, and the invariant ADR-004 enforces.
 *
 * <p>These are the tests the architecture was written to pass. The first one replays the exact
 * trace from ADR-001 — two withdrawals 50ms apart, not even concurrent — which is the scenario
 * that design's own opening paragraph described and its lock did not stop.
 *
 * <p>Runs under the composed {@code @IntegrationTest} annotation rather than a bare
 * {@code @SpringBootTest} with its own {@code @Container}. Two reasons, both practical: a bare
 * {@code @SpringBootTest} activates no profile, so the production {@code SecurityConfig} applies and
 * the context refuses to start without an issuer — working as designed, but not what these tests are
 * about. And Spring caches contexts by configuration, so a second, subtly different declaration here
 * would start a second PostgreSQL and a second context for the same suite.
 */
@IntegrationTest
class OverdraftIT {

    @Autowired LedgerService ledger;
    @Autowired JdbcTemplate jdbc;
    @Autowired LedgerRepository journal;

    UUID account;

    @BeforeEach
    void reset() {
        // TRUNCATE, not DELETE: the append-only rule on ledger_entries makes DELETE a no-op,
        // which then blocks removing the buckets those entries reference. The journal defending
        // itself against the test fixture is the rule working exactly as intended.
        jdbc.update("""
                TRUNCATE ledger_entries, reservation_allocations, reservations,
                         balance_buckets, account_balances, ledger_config
                """);
        jdbc.update("INSERT INTO ledger_config (currency, minor_units) VALUES ('EUR', 2)");
        account = accountWithCash(new BigDecimal("100.0000"));
    }

    // ---------------------------------------------------------------- the ADR-001 trace

    @Test
    @DisplayName("two withdrawals 50ms apart cannot both be approved — the exact ADR-001 trace")
    void theTraceThatKilledAdr001() throws Exception {
        // t=0..4ms — withdrawal A is approved and its hold is committed
        var a = ledger.reserve(account, new BigDecimal("60.0000"), "withdrawal-A", null);
        assertThat(a.state()).isEqualTo("HELD");

        Thread.sleep(50);   // the gap that defeated the lock: it was released 46ms ago

        // t=50ms — under ADR-001 this read 100.00 and approved. Here the hold is already
        // committed to the same rows, so B sees available = 40.00 and is refused.
        assertThatThrownBy(() -> ledger.reserve(account, new BigDecimal("60.0000"), "withdrawal-B", null))
                .isInstanceOf(LedgerService.InsufficientFunds.class)
                .hasMessageContaining("40.0000");

        var balance = ledger.balance(account);
        assertThat(balance.available()).isEqualByComparingTo("40.0000");
        assertThat(balance.posted()).isEqualByComparingTo("100.0000");   // nothing has settled yet
        assertThat(balance.held()).isEqualByComparingTo("60.0000");
    }

    @Test
    @DisplayName("eight concurrent withdrawals against a balance that funds one — exactly one wins")
    void concurrencyCannotOverdraw() throws Exception {
        int threads = 8;
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(threads);
        var accepted = new AtomicInteger();
        var refused = new AtomicInteger();

        var futures = new java.util.ArrayList<Future<?>>();
        for (int i = 0; i < threads; i++) {
            final String key = "concurrent-" + i;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    ledger.reserve(account, new BigDecimal("60.0000"), key, null);
                    accepted.incrementAndGet();
                } catch (LedgerService.InsufficientFunds e) {
                    refused.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(accepted.get()).isEqualTo(1);
        assertThat(refused.get()).isEqualTo(threads - 1);
        assertThat(ledger.balance(account).available()).isEqualByComparingTo("40.0000");
    }

    // ---------------------------------------------------------------- the backstop

    @Test
    @DisplayName("the database refuses an overdraft even when the application logic is wrong")
    void theSchemaIsTheBackstop() {
        // Bypass the service entirely — this is what a future bug looks like.
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE account_balances SET held_total = 500 WHERE account_id = ?", account))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("account_not_overdrawn");

        assertThat(ledger.balance(account).available()).isEqualByComparingTo("100.0000");
    }

    @Test
    @DisplayName("the journal is append-only — an UPDATE silently changes nothing")
    void theJournalCannotBeRewritten() {
        var bucket = jdbc.queryForObject(
                "SELECT bucket_id FROM balance_buckets WHERE account_id = ?", UUID.class, account);
        var txn = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ledger_entries (entry_id, account_id, bucket_id, direction, amount, transaction_id)
                VALUES (?, ?, ?, 'CREDIT', 100.0000, ?)
                """, UUID.randomUUID(), account, bucket, txn);

        jdbc.update("UPDATE ledger_entries SET amount = 1 WHERE transaction_id = ?", txn);
        jdbc.update("DELETE FROM ledger_entries WHERE transaction_id = ?", txn);

        assertThat(jdbc.queryForObject(
                "SELECT amount FROM ledger_entries WHERE transaction_id = ?", BigDecimal.class, txn))
                .isEqualByComparingTo("100.0000");
    }

    @Test
    @DisplayName("settlement is idempotent — a redelivered event cannot double-post")
    void settlementCannotDoublePost() {
        var bucket = jdbc.queryForObject(
                "SELECT bucket_id FROM balance_buckets WHERE account_id = ?", UUID.class, account);
        var txn = UUID.randomUUID();
        String insert = """
                INSERT INTO ledger_entries (entry_id, account_id, bucket_id, direction, amount, transaction_id)
                VALUES (?, ?, ?, 'DEBIT', 60.0000, ?)
                """;
        jdbc.update(insert, UUID.randomUUID(), account, bucket, txn);

        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), account, bucket, txn))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------------------------------------------------------------- typed money

    @Test
    @DisplayName("DEBT is an obligation, not a funding source")
    void debtCannotBeSpent() {
        var indebted = accountWithCash(new BigDecimal("50.0000"));
        jdbc.update("""
                INSERT INTO balance_buckets (bucket_id, account_id, bucket_type, posted_balance,
                                             withdrawable, spend_priority)
                VALUES (?, ?, 'DEBT', 100.0000, FALSE, 0)
                """, UUID.randomUUID(), indebted);
        jdbc.update("UPDATE account_balances SET debt_total = 100.0000 WHERE account_id = ?", indebted);

        // 50 cash + 100 debt is not 150 of spending power.
        assertThatThrownBy(() -> ledger.reserve(indebted, new BigDecimal("80.0000"), "spend-the-debt", null))
                .isInstanceOf(LedgerService.InsufficientFunds.class);

        var b = ledger.balance(indebted);
        assertThat(b.available()).isEqualByComparingTo("50.0000");
        assertThat(b.debt()).isEqualByComparingTo("100.0000");
    }

    @Test
    @DisplayName("promotional money spends before cash, and only cash is withdrawable")
    void spendPriorityAndWithdrawability() {
        jdbc.update("""
                INSERT INTO balance_buckets (bucket_id, account_id, bucket_type, source_module, grant_id,
                                             posted_balance, withdrawable, wagering_remaining, spend_priority)
                VALUES (?, ?, 'BONUS', 'bonus', ?, 60.0000, FALSE, 600.0000, 10)
                """, UUID.randomUUID(), account, UUID.randomUUID());
        jdbc.update("UPDATE account_balances SET posted_balance = posted_balance + 60.0000 WHERE account_id = ?",
                account);

        // 160 to bet with, 100 of it withdrawable — the distinction a single balance cannot express.
        var before = ledger.balance(account);
        assertThat(before.available()).isEqualByComparingTo("160.0000");
        assertThat(before.withdrawable()).isEqualByComparingTo("100.0000");

        var r = ledger.reserve(account, new BigDecimal("80.0000"), "mixed-stake", null);

        // 60 of bonus (priority 10) before 20 of cash (priority 100) — the user does not
        // forfeit promotional money by spending cash first.
        assertThat(r.allocations()).extracting(LedgerService.Allocation::bucketType)
                .containsExactly("BONUS", "CASH");
        assertThat(r.allocations()).extracting(LedgerService.Allocation::amount)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("60.0000"), new BigDecimal("20.0000"));

        // The stake took bonus money first, so only 20 of the hold came out of cash:
        // withdrawable falls 100 -> 80, not 100 -> 20.
        assertThat(ledger.balance(account).withdrawable()).isEqualByComparingTo("80.0000");
        assertThat(ledger.balance(account).available()).isEqualByComparingTo("80.0000");
    }

    // ---------------------------------------------------------------- holds

    @Test
    @DisplayName("a retried reserve returns the existing hold, never a second one")
    void retriesAreIdempotent() {
        var first = ledger.reserve(account, new BigDecimal("60.0000"), "same-key", null);
        var retry = ledger.reserve(account, new BigDecimal("60.0000"), "same-key", null);

        assertThat(retry.reservationId()).isEqualTo(first.reservationId());
        assertThat(ledger.balance(account).held()).isEqualByComparingTo("60.0000");   // not 120
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservations", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("the caller sets the hold lifetime, within the deployment's ceiling")
    void holdLifetimeBelongsToTheCaller() {
        var wager = ledger.reserve(account, new BigDecimal("20.0000"), "three-day-wager", Duration.ofDays(3));
        assertThat(wager.expiresAt()).isAfter(java.time.Instant.now().plus(Duration.ofDays(2)));

        var auth = ledger.reserve(account, new BigDecimal("20.0000"), "instant-purchase", null);
        assertThat(auth.expiresAt()).isBefore(java.time.Instant.now().plus(Duration.ofMinutes(6)));

        assertThatThrownBy(() -> ledger.reserve(account, new BigDecimal("1.0000"), "forever", Duration.ofDays(400)))
                .isInstanceOf(LedgerService.HoldTtlTooLong.class);
    }

    @Test
    @DisplayName("releasing a hold returns the funds and writes no journal entry")
    void releaseLeavesNoTrace() {
        var r = ledger.reserve(account, new BigDecimal("60.0000"), "to-be-released", null);
        ledger.release(r.reservationId());

        assertThat(ledger.balance(account).available()).isEqualByComparingTo("100.0000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT state FROM reservations WHERE reservation_id = ?", String.class, r.reservationId()))
                .isEqualTo("RELEASED");
    }

    // ---------------------------------------------------------------- settlement

    @Test
    @DisplayName("settling a hold spends the money — posted falls, available does not move")
    void settleSpendsHeldMoney() {
        var r = ledger.reserve(account, new BigDecimal("60.0000"), "settle-me", null);

        var before = ledger.balance(account);
        assertThat(before.posted()).isEqualByComparingTo("100.0000");
        assertThat(before.held()).isEqualByComparingTo("60.0000");
        assertThat(before.available()).isEqualByComparingTo("40.0000");

        ledger.settle(r.reservationId(), UUID.randomUUID());

        // The hold becomes a spend: 100 posted less the 60 captured, and the 60 that was held is
        // no longer held. Available was already 40 before settlement and must not move — the
        // money stopped being spendable when it was reserved, not when it was captured.
        var after = ledger.balance(account);
        assertThat(after.posted()).isEqualByComparingTo("40.0000");
        assertThat(after.held()).isEqualByComparingTo("0.0000");
        assertThat(after.available()).isEqualByComparingTo("40.0000");
    }

    @Test
    @DisplayName("settlement journals one debit per bucket the hold drew from")
    void settleJournalsOneDebitPerAllocation() {
        jdbc.update("""
                INSERT INTO balance_buckets (bucket_id, account_id, bucket_type, source_module, grant_id,
                                             posted_balance, withdrawable, wagering_remaining, spend_priority)
                VALUES (?, ?, 'BONUS', 'bonus', ?, 60.0000, FALSE, 600.0000, 10)
                """, UUID.randomUUID(), account, UUID.randomUUID());
        jdbc.update("UPDATE account_balances SET posted_balance = posted_balance + 60.0000 WHERE account_id = ?",
                account);

        // 60 of bonus (priority 10) then 20 of cash (priority 100), as spendPriority... establishes.
        var r = ledger.reserve(account, new BigDecimal("80.0000"), "journal-me", null);
        var transaction = UUID.randomUUID();

        ledger.settle(r.reservationId(), transaction);

        // The journal is read through the repository rather than by querying ledger_entries here:
        // what the entries mean is the dao layer's contract, and a service test that knew the
        // column names would break on a schema change that changed no behaviour.
        var entries = journal.entriesFor(r.reservationId());
        assertThat(entries).hasSize(2);
        assertThat(entries).allSatisfy(e -> {
            assertThat(e.direction()).isEqualTo("DEBIT");
            assertThat(e.transactionId()).isEqualTo(transaction);
        });
        assertThat(entries).extracting(LedgerRepository.JournalEntryRow::amount)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactlyInAnyOrder(new BigDecimal("60.0000"), new BigDecimal("20.0000"));

        // Each debit lands on the bucket that funded it, not all on one.
        assertThat(entries).extracting(LedgerRepository.JournalEntryRow::bucketId)
                .containsExactlyInAnyOrderElementsOf(
                        r.allocations().stream().map(LedgerService.Allocation::bucketId).toList());
    }

    // ---------------------------------------------------------------- fixtures

    private UUID accountWithCash(BigDecimal cash) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO account_balances (account_id, posted_balance) VALUES (?, ?)", id, cash);
        jdbc.update("""
                INSERT INTO balance_buckets (bucket_id, account_id, bucket_type, posted_balance,
                                             withdrawable, spend_priority)
                VALUES (?, ?, 'CASH', ?, TRUE, 100)
                """, UUID.randomUUID(), id, cash);
        return id;
    }
}
