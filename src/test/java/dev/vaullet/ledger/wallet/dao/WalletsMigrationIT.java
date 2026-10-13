package dev.vaullet.ledger.wallet.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vaullet.common.test.IntegrationTest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V2's backfill, run the way a database that already holds V1 data would meet it.
 *
 * <p>Flyway migrates a schema of this test's own to V1, the test writes V1-shaped rows into it, and
 * Flyway takes it on to V2. The schema is separate so that the application's, which every other
 * test uses and which is already at the latest version, is never touched; the statements below
 * name it explicitly rather than changing a pooled connection's search_path.
 */
@IntegrationTest
class WalletsMigrationIT {

    private static final String SCHEMA = "wallets_migration_it";

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void dropSchema() {
        jdbc.update("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @Test
    @DisplayName("V2 gives each account a wallet per type it holds buckets of, and links every bucket to it")
    void backfillsWalletsFromV1Buckets() {
        migrateTo("1");
        UUID mixed = account();
        UUID cash = bucket(mixed, "CASH");
        UUID debt = bucket(mixed, "DEBT");
        UUID bonus = bucket(mixed, "BONUS");
        UUID loyalty = bucket(mixed, "LOYALTY");
        UUID referral = bucket(mixed, "REFERRAL");
        UUID cashOnly = account();
        UUID onlyCash = bucket(cashOnly, "CASH");
        UUID bucketless = account();

        migrateTo("2");

        assertThat(walletsOf(mixed)).containsOnlyKeys("REAL", "BONUS");
        assertThat(walletsOf(cashOnly)).containsOnlyKeys("REAL");
        assertThat(walletsOf(bucketless)).isEmpty();

        assertThat(List.of(cash, debt)).allSatisfy(b -> assertThat(walletOf(b)).isEqualTo(walletsOf(mixed).get("REAL")));
        assertThat(List.of(bonus, loyalty, referral))
                .allSatisfy(b -> assertThat(walletOf(b)).isEqualTo(walletsOf(mixed).get("BONUS")));
        assertThat(walletOf(onlyCash)).isEqualTo(walletsOf(cashOnly).get("REAL"));

        assertThat(jdbc.queryForList("SELECT status FROM " + SCHEMA + ".wallets WHERE closed_at IS NULL", String.class))
                .hasSize(3)
                .containsOnly("ACTIVE");

        // From here on a bucket cannot exist outside a wallet.
        assertThatThrownBy(() -> jdbc.update("INSERT INTO " + SCHEMA + ".balance_buckets "
                        + "(bucket_id, account_id, bucket_type, withdrawable, spend_priority) "
                        + "VALUES (?, ?, 'BONUS', FALSE, 10)", UUID.randomUUID(), cashOnly))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("wallet_id");
    }

    @Test
    @DisplayName("V2 puts a bucket only in a wallet of its own account")
    void aBucketStaysInItsOwnAccount() {
        migrateTo("2");
        UUID owner = account();
        UUID other = account();
        UUID ownersBonus = wallet(owner, "BONUS");

        bucketIn(ownersBonus, owner);
        // Another account's wallet would put this money under that wallet's freeze, and into its
        // balance and its emptiness check.
        assertThatThrownBy(() -> bucketIn(ownersBonus, other))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("balance_buckets_wallet_fk");
    }

    @Test
    @DisplayName("V2 on an empty V1 database creates no wallets")
    void migratesAnEmptyDatabase() {
        migrateTo("1");
        migrateTo("2");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA + ".wallets", Integer.class)).isZero();
    }

    private void migrateTo(String version) {
        Flyway.configure()
                .dataSource(dataSource)
                .schemas(SCHEMA)
                .locations("classpath:db/migration")
                .target(version)
                .load()
                .migrate();
    }

    private UUID account() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + SCHEMA + ".account_balances (account_id) VALUES (?)", id);
        return id;
    }

    /** A V1 bucket: no wallet_id column yet. */
    private UUID bucket(UUID accountId, String type) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + SCHEMA + ".balance_buckets "
                        + "(bucket_id, account_id, bucket_type, withdrawable, spend_priority) VALUES (?, ?, ?, ?, ?)",
                id, accountId, type, "CASH".equals(type), "CASH".equals(type) ? 100 : 10);
        return id;
    }

    private UUID wallet(UUID accountId, String type) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + SCHEMA + ".wallets (wallet_id, account_id, wallet_type) VALUES (?, ?, ?)",
                id, accountId, type);
        return id;
    }

    /** A V2 bucket: a grant in the given wallet, booked to the given account. */
    private void bucketIn(UUID walletId, UUID accountId) {
        jdbc.update("INSERT INTO " + SCHEMA + ".balance_buckets "
                        + "(bucket_id, account_id, wallet_id, bucket_type, withdrawable, spend_priority) "
                        + "VALUES (?, ?, ?, 'BONUS', FALSE, 10)",
                UUID.randomUUID(), accountId, walletId);
    }

    /** wallet_type → wallet_id. */
    private Map<String, UUID> walletsOf(UUID accountId) {
        var wallets = new HashMap<String, UUID>();
        jdbc.query("SELECT wallet_type, wallet_id FROM " + SCHEMA + ".wallets WHERE account_id = ?",
                rs -> {
                    wallets.put(rs.getString("wallet_type"), rs.getObject("wallet_id", UUID.class));
                }, accountId);
        return wallets;
    }

    private UUID walletOf(UUID bucketId) {
        return jdbc.queryForObject("SELECT wallet_id FROM " + SCHEMA + ".balance_buckets WHERE bucket_id = ?",
                UUID.class, bucketId);
    }
}
