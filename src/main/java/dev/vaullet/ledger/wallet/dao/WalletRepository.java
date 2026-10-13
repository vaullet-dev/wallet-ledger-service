package dev.vaullet.ledger.wallet.dao;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Every statement the wallet feature issues, and nothing else.
 *
 * <p>The same contract as {@code LedgerRepository}, for the same reason: the SQL is the design
 * (ADR-004), so it is written out where a reviewer can read it. Nothing here decides anything —
 * whether a wallet may be opened, frozen or closed, and what its balance comes to, is
 * {@code WalletService}'s. Statuses and types travel as their raw column text, because the
 * service's enums live in a layer this one may not depend on.
 */
@Repository
public class WalletRepository {

    private final JdbcTemplate jdbc;

    public WalletRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A wallet, as stored. */
    public record WalletRow(UUID walletId, UUID accountId, String type, String status,
                            Instant createdAt, Instant updatedAt, @Nullable Instant closedAt) {}

    /**
     * A bucket, as stored. {@code expired} is judged by the database clock, the one the reserve path
     * judges it by: a bucket past its expiry still holds its money until the sweeper zeroes it.
     */
    public record BucketRow(UUID bucketId, String bucketType, BigDecimal posted, BigDecimal held,
                            boolean withdrawable, BigDecimal wageringRemaining, @Nullable Instant expiresAt,
                            boolean expired) {}

    /**
     * Makes the account known to the ledger if it is not already. Its {@code account_balances} row is
     * the lock every path that touches the account's money takes first (ADR-004), so it has to exist
     * before the account's first wallet can be opened under it.
     */
    public void insertAccountIfAbsent(UUID accountId) {
        jdbc.update("INSERT INTO account_balances (account_id) VALUES (?) ON CONFLICT (account_id) DO NOTHING",
                accountId);
    }

    /**
     * ADR-004's lock anchor, the same row {@code LedgerRepository.lockAccount} locks, taken before any
     * wallet or bucket row is written. A status change and a reserve on one account therefore
     * serialize: a wallet cannot be frozen half-way through an allocation that is drawing from it.
     *
     * <p>The row exists whenever this is called: {@code open} inserts it first, and every wallet's
     * account has one (the {@code wallets.account_id} foreign key).
     */
    public void lockAccount(UUID accountId) {
        jdbc.queryForList("SELECT account_id FROM account_balances WHERE account_id = ? FOR UPDATE",
                UUID.class, accountId);
    }

    /**
     * Inserts the wallet unless the account already has one of this type; true when this call
     * inserted it. The conflict on {@code wallets_one_per_type} is how a repeated open learns that it
     * is a repeat.
     */
    public boolean insertIfAbsent(UUID walletId, UUID accountId, String type) {
        return jdbc.update("""
                INSERT INTO wallets (wallet_id, account_id, wallet_type)
                VALUES (?, ?, ?)
                ON CONFLICT (account_id, wallet_type) DO NOTHING
                """, walletId, accountId, type) == 1;
    }

    /** An empty bucket: nothing posted, held or owed until money is booked into it. */
    public void insertBucket(UUID bucketId, UUID accountId, UUID walletId, String bucketType,
                             boolean withdrawable, int spendPriority) {
        jdbc.update("""
                INSERT INTO balance_buckets (bucket_id, account_id, wallet_id, bucket_type,
                                             withdrawable, spend_priority)
                VALUES (?, ?, ?, ?, ?, ?)
                """, bucketId, accountId, walletId, bucketType, withdrawable, spendPriority);
    }

    public Optional<WalletRow> find(UUID walletId) {
        return jdbc.query(SELECT_WALLET + " WHERE wallet_id = ?", WALLET, walletId).stream().findFirst();
    }

    public Optional<WalletRow> findByAccountAndType(UUID accountId, String type) {
        return jdbc.query(SELECT_WALLET + " WHERE account_id = ? AND wallet_type = ?", WALLET, accountId, type)
                .stream().findFirst();
    }

    public List<WalletRow> findByAccount(UUID accountId) {
        return jdbc.query(SELECT_WALLET + " WHERE account_id = ?", WALLET, accountId);
    }

    /**
     * One page of wallets in {@code (created_at, wallet_id)} order, the order
     * {@code wallets_keyset_ix} holds, starting strictly after the given position.
     *
     * <p>Assembled from the filters actually given, rather than one statement with
     * {@code (? IS NULL OR column = ?)} for each: that form hides from the planner which predicates
     * apply, and once PostgreSQL settles on a generic plan it can no longer use the keyset index.
     *
     * @param afterCreatedAt with {@code afterWalletId}, the last wallet of the previous page; both
     *     null for the first page
     */
    public List<WalletRow> search(@Nullable UUID accountId, @Nullable String type, @Nullable String status,
                                  @Nullable Instant afterCreatedAt, @Nullable UUID afterWalletId, int limit) {
        StringBuilder sql = new StringBuilder(SELECT_WALLET).append(" WHERE TRUE");
        List<Object> args = new ArrayList<>();
        if (accountId != null) {
            sql.append(" AND account_id = ?");
            args.add(accountId);
        }
        if (type != null) {
            sql.append(" AND wallet_type = ?");
            args.add(type);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (afterCreatedAt != null && afterWalletId != null) {
            sql.append(" AND (created_at, wallet_id) > (?, ?)");
            args.add(afterCreatedAt.atOffset(ZoneOffset.UTC));
            args.add(afterWalletId);
        }
        sql.append(" ORDER BY created_at, wallet_id LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), WALLET, args.toArray());
    }

    public void updateStatus(UUID walletId, String status) {
        jdbc.update("UPDATE wallets SET status = ?, updated_at = now() WHERE wallet_id = ?", status, walletId);
    }

    /** Terminal. {@code wallets_closed_at_iff_closed} is why the date moves with the status. */
    public void close(UUID walletId) {
        jdbc.update("""
                UPDATE wallets
                   SET status     = 'CLOSED',
                       closed_at  = now(),
                       updated_at = now()
                 WHERE wallet_id = ?
                """, walletId);
    }

    /** The wallet's buckets: CASH first, then in the order the reserve path would spend them. */
    public List<BucketRow> bucketsOf(UUID walletId) {
        return jdbc.query("""
                SELECT bucket_id, bucket_type, posted_balance, held_total, withdrawable,
                       wagering_remaining, expires_at,
                       expires_at IS NOT NULL AND expires_at <= now() AS expired
                  FROM balance_buckets
                 WHERE wallet_id = ?
                 ORDER BY bucket_type <> 'CASH', spend_priority, bucket_id
                """,
                (rs, i) -> new BucketRow(
                        rs.getObject("bucket_id", UUID.class),
                        rs.getString("bucket_type"),
                        rs.getBigDecimal("posted_balance"),
                        rs.getBigDecimal("held_total"),
                        rs.getBoolean("withdrawable"),
                        rs.getBigDecimal("wagering_remaining"),
                        instant(rs.getObject("expires_at", OffsetDateTime.class)),
                        rs.getBoolean("expired")),
                walletId);
    }

    private static final String SELECT_WALLET = """
            SELECT wallet_id, account_id, wallet_type, status, created_at, updated_at, closed_at
              FROM wallets""";

    private static final RowMapper<WalletRow> WALLET = (rs, i) -> new WalletRow(
            rs.getObject("wallet_id", UUID.class),
            rs.getObject("account_id", UUID.class),
            rs.getString("wallet_type"),
            rs.getString("status"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
            instant(rs.getObject("closed_at", OffsetDateTime.class)));

    private static @Nullable Instant instant(@Nullable OffsetDateTime timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
