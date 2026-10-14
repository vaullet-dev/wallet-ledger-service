package dev.vaullet.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vaullet.common.test.IntegrationTest;
import dev.vaullet.ledger.config.dao.LedgerConfigRepository;
import dev.vaullet.ledger.config.dao.LedgerConfigRepository.LedgerConfigRow;
import dev.vaullet.ledger.reservation.dao.LedgerRepository;
import dev.vaullet.ledger.reservation.service.LedgerService;
import dev.vaullet.ledger.wallet.dao.WalletRepository;
import dev.vaullet.ledger.wallet.service.Wallet;
import dev.vaullet.ledger.wallet.service.WalletBalance;
import dev.vaullet.ledger.wallet.service.WalletSearch;
import dev.vaullet.ledger.wallet.service.WalletService;
import dev.vaullet.ledger.wallet.service.WalletStatus;
import dev.vaullet.ledger.wallet.service.WalletType;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V3__grant_app_role.sql, tested as the role it grants to.
 *
 * <p>Every other test connects as the database's owner, which may do anything, so none of them would
 * notice a missing grant, and a missing grant shows only in production, at the first request that
 * needs it. Here the service's own paths run on a connection that is {@code ledger_app}'s and nobody
 * else's: the repositories and services are built on it by hand, and a {@code TransactionTemplate}
 * on the same connection does what {@code @Transactional} does in the application. A statement that
 * needs more than V3 grants fails a test here.
 *
 * <p>The other half is what {@code ledger_app} must never do, refused by the database rather than by
 * the code: no DELETE or TRUNCATE anywhere, no UPDATE of what is written once, no INSERT into
 * {@code ledger_config}, nothing at all on {@code flyway_schema_history}, and no DDL.
 *
 * <p>V3 leaves the role NOLOGIN, with no password, as a migration must. Each test lends it a login
 * from the owner's connection and takes it back afterwards.
 */
@IntegrationTest
class AppRoleGrantsIT {

    /** SQLSTATE insufficient_privilege: the database said no, not the code. */
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    /** Lives for one test, on a database the test suite owns. */
    private static final String TEST_ONLY_PASSWORD = "test-only-ledger-app";

    @Autowired JdbcTemplate owner;

    JdbcTemplate app;
    TransactionTemplate transaction;
    LedgerConfigRepository config;
    LedgerRepository journal;
    LedgerService ledger;
    WalletService wallets;

    @BeforeEach
    void connectAsLedgerApp() {
        // TRUNCATE, not DELETE: see OverdraftIT -- the journal's append-only rule blocks a DELETE.
        owner.update("""
                TRUNCATE ledger_entries, reservation_allocations, reservations,
                         balance_buckets, wallets, account_balances, ledger_config
                """);
        // What the migration Job's run of LedgerConfigProvisioner leaves behind in a cluster.
        owner.update("INSERT INTO ledger_config (currency, minor_units) VALUES ('EUR', 2)");
        owner.execute("ALTER ROLE ledger_app LOGIN PASSWORD '" + TEST_ONLY_PASSWORD + "'");

        DataSource asLedgerApp = new DriverManagerDataSource(databaseUrl(), "ledger_app", TEST_ONLY_PASSWORD);
        app = new JdbcTemplate(asLedgerApp);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(asLedgerApp));
        config = new LedgerConfigRepository(app);
        journal = new LedgerRepository(app);
        ledger = new LedgerService(journal);
        wallets = new WalletService(new WalletRepository(app), config);

        // Every assertion below means something only because this is not the owner's connection.
        assertThat(app.queryForObject("SELECT current_user", String.class)).isEqualTo("ledger_app");
    }

    @AfterEach
    void putEverythingBack() {
        owner.execute("ALTER ROLE ledger_app NOLOGIN PASSWORD NULL");
        // One test empties ledger_config, and a context started after this class expects the test
        // profile's EUR, as LedgerConfigProvisionerIT notes.
        owner.update("TRUNCATE ledger_config");
        owner.update("INSERT INTO ledger_config (currency, minor_units) VALUES ('EUR', 2)");
    }

    // ---------------------------------------------------------------- what the service does

    @Test
    @DisplayName("ledger_app opens wallets, freezes, unfreezes and closes them, and finds them again")
    void walletsAsLedgerApp() {
        UUID account = UUID.randomUUID();

        Wallet real = inTransaction(() -> wallets.open(account, WalletType.REAL)).wallet();
        Wallet bonus = inTransaction(() -> wallets.open(account, WalletType.BONUS)).wallet();
        // A repeat runs into the rows the first open made: both ON CONFLICT inserts, and the lock.
        assertThat(inTransaction(() -> wallets.open(account, WalletType.REAL)).created()).isFalse();

        inTransaction(() -> wallets.changeStatus(real.walletId(), WalletStatus.FROZEN));
        inTransaction(() -> wallets.changeStatus(real.walletId(), WalletStatus.ACTIVE));
        inTransaction(() -> wallets.close(bonus.walletId()));

        assertThat(wallets.get(real.walletId()).status()).isEqualTo(WalletStatus.ACTIVE);
        assertThat(wallets.get(bonus.walletId()).status()).isEqualTo(WalletStatus.CLOSED);
        assertThat(wallets.search(new WalletSearch(account, null, null, WalletSearch.DEFAULT_LIMIT, null)).data())
                .extracting(Wallet::walletId)
                .containsExactlyInAnyOrder(real.walletId(), bonus.walletId());
    }

    @Test
    @DisplayName("ledger_app places a hold and settles it, places another and releases it, and reads every balance")
    void moneyAsLedgerApp() {
        UUID account = UUID.randomUUID();
        Wallet real = inTransaction(() -> wallets.open(account, WalletType.REAL)).wallet();
        fundCash(account, "100.0000");

        LedgerService.Reservation spent =
                inTransaction(() -> ledger.reserve(account, new BigDecimal("60.0000"), "grants-spent", null));
        inTransaction(() -> ledger.settle(spent.reservationId(), UUID.randomUUID()));
        LedgerService.Reservation kept =
                inTransaction(() -> ledger.reserve(account, new BigDecimal("10.0000"), "grants-kept", null));
        inTransaction(() -> ledger.release(kept.reservationId()));
        // A retried reserve finds the first hold: the idempotency read, under the same row lock.
        assertThat(inTransaction(() -> ledger.reserve(account, new BigDecimal("60.0000"), "grants-spent", null))
                .reservationId()).isEqualTo(spent.reservationId());

        assertThat(ledger.find(spent.reservationId()).state()).isEqualTo("SETTLED");
        assertThat(ledger.find(kept.reservationId()).state()).isEqualTo("RELEASED");
        assertThat(journal.entriesFor(spent.reservationId())).singleElement().satisfies(entry -> {
            assertThat(entry.direction()).isEqualTo("DEBIT");
            assertThat(entry.amount()).isEqualByComparingTo("60.0000");
        });

        LedgerService.Balance balance = ledger.balance(account);
        assertThat(balance.posted()).isEqualByComparingTo("40.0000");
        assertThat(balance.held()).isEqualByComparingTo("0");
        assertThat(balance.available()).isEqualByComparingTo("40.0000");
        assertThat(balance.withdrawable()).isEqualByComparingTo("40.0000");
        WalletBalance cash = wallets.balance(real.walletId());
        assertThat(cash.currency()).isEqualTo("EUR");
        assertThat(cash.available()).isEqualByComparingTo("40.0000");
        assertThat(wallets.balancesOf(account)).extracting(WalletBalance::walletId).containsExactly(real.walletId());
    }

    @Test
    @DisplayName("ledger_app reads the currency the migration Job recorded, and cannot record one itself")
    void ledgerAppReadsTheCurrencyOnly() {
        assertThat(config.find()).contains(new LedgerConfigRow("EUR", 2));
        assertThat(journal.maxHoldSeconds()).isPositive();

        assertRefused(() -> config.insertIfAbsent("EUR", 2));
        // Not even before the Job's first run, with the table empty: the service then fails to start
        // instead of choosing the deployment's currency itself.
        owner.update("TRUNCATE ledger_config");
        assertRefused(() -> config.insertIfAbsent("EUR", 2));
    }

    // ---------------------------------------------------------------- what it must never do

    @Test
    @DisplayName("the journal: UPDATE and DELETE change nothing, as for the owner, and TRUNCATE is refused")
    void theJournalStaysAsWritten() {
        UUID transactionId = journalEntry();

        // V1's RULEs rewrite both statements into nothing before any privilege is checked, so they are
        // not refused but empty, exactly as for the owner. The privileges are withheld all the same.
        assertThat(app.update("UPDATE ledger_entries SET amount = 1 WHERE transaction_id = ?", transactionId))
                .isZero();
        assertThat(app.update("DELETE FROM ledger_entries WHERE transaction_id = ?", transactionId)).isZero();
        assertThat(owner.queryForObject("SELECT amount FROM ledger_entries WHERE transaction_id = ?",
                BigDecimal.class, transactionId)).isEqualByComparingTo("100.0000");
        assertThat(holds("ledger_entries", "UPDATE")).isFalse();
        assertThat(holds("ledger_entries", "DELETE")).isFalse();

        // The one statement no rule sees.
        assertRefused(() -> app.execute("TRUNCATE ledger_entries"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "account_balances", "balance_buckets", "reservations", "reservation_allocations", "wallets",
            "ledger_config"})
    @DisplayName("nothing is removed: DELETE and TRUNCATE are refused")
    void nothingIsRemoved(String table) {
        assertRefused(() -> app.update("DELETE FROM " + table));
        assertRefused(() -> app.execute("TRUNCATE " + table));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "UPDATE reservation_allocations SET amount = 1",
            "UPDATE ledger_config SET currency = 'USD'"})
    @DisplayName("what is written once stays as written: UPDATE is refused")
    void writtenOnceStaysWritten(String statement) {
        assertRefused(() -> app.execute(statement));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "SELECT count(*) FROM flyway_schema_history",
            "INSERT INTO flyway_schema_history DEFAULT VALUES",
            "UPDATE flyway_schema_history SET checksum = 0",
            "DELETE FROM flyway_schema_history"})
    @DisplayName("the migration history is out of reach, so validate-on-migrate can trust it")
    void migrationHistoryIsOutOfReach(String statement) {
        assertRefused(() -> app.execute(statement));
    }

    @Test
    @DisplayName("no DDL: ledger_app can neither alter nor drop a table, nor grant itself more")
    void noDdl() {
        assertRefused(() -> app.execute("ALTER TABLE wallets ADD COLUMN note TEXT"));
        assertRefused(() -> app.execute("DROP TABLE ledger_entries"));

        // A role cannot hand itself what it was never given: PostgreSQL warns, and grants nothing.
        app.execute("GRANT DELETE ON wallets TO ledger_app");
        assertThat(holds("wallets", "DELETE")).isFalse();
    }

    // ---------------------------------------------------------------- fixtures

    /** The database the suite runs on, wherever that is: the URL of the owner's own connection. */
    private String databaseUrl() {
        return Objects.requireNonNull(
                owner.execute((ConnectionCallback<String>) connection -> connection.getMetaData().getURL()));
    }

    /** {@code @Transactional}, by hand: the service method's own transaction, on ledger_app's connection. */
    private <T> T inTransaction(Supplier<T> work) {
        return Objects.requireNonNull(transaction.execute(status -> work.get()));
    }

    /** The same, for a service method that returns nothing. */
    private void inTransaction(Runnable work) {
        transaction.executeWithoutResult(status -> work.run());
    }

    /**
     * Books money into the account's CASH bucket, keeping the aggregate in step. As the owner, and by
     * SQL, as in WalletServiceIT: the credit path arrives with a later issue.
     */
    private void fundCash(UUID accountId, String amount) {
        BigDecimal money = new BigDecimal(amount);
        owner.update("UPDATE balance_buckets SET posted_balance = posted_balance + ? "
                + "WHERE account_id = ? AND bucket_type = 'CASH'", money, accountId);
        owner.update("UPDATE account_balances SET posted_balance = posted_balance + ? WHERE account_id = ?",
                money, accountId);
    }

    /** One CREDIT in the journal, written by the owner, for ledger_app to try to change. */
    private UUID journalEntry() {
        UUID account = UUID.randomUUID();
        UUID wallet = UUID.randomUUID();
        UUID bucket = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        owner.update("INSERT INTO account_balances (account_id, posted_balance) VALUES (?, 100.0000)", account);
        owner.update("INSERT INTO wallets (wallet_id, account_id, wallet_type) VALUES (?, ?, 'REAL')",
                wallet, account);
        owner.update("""
                INSERT INTO balance_buckets (bucket_id, account_id, wallet_id, bucket_type, posted_balance,
                                             withdrawable, spend_priority)
                VALUES (?, ?, ?, 'CASH', 100.0000, TRUE, 100)
                """, bucket, account, wallet);
        owner.update("""
                INSERT INTO ledger_entries (entry_id, account_id, bucket_id, direction, amount, transaction_id)
                VALUES (?, ?, ?, 'CREDIT', 100.0000, ?)
                """, UUID.randomUUID(), account, bucket, transactionId);
        return transactionId;
    }

    /** Whether ledger_app holds a privilege on a table, asked of the catalog rather than by trying. */
    private boolean holds(String table, String privilege) {
        return Boolean.TRUE.equals(owner.queryForObject(
                "SELECT has_table_privilege('ledger_app', ?, ?)", Boolean.class, table, privilege));
    }

    /** Refused by PostgreSQL for want of a privilege: the only refusal that counts here. */
    private static void assertRefused(ThrowingCallable statement) {
        assertThatThrownBy(statement)
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .isInstanceOfSatisfying(SQLException.class,
                        refusal -> assertThat(refusal.getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE));
    }
}
