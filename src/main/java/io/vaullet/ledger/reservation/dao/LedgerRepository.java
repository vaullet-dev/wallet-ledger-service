package io.vaullet.ledger.reservation.dao;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Every statement the ledger issues, and nothing else.
 *
 * <p>Hand-written SQL rather than an ORM, deliberately: the correctness argument in ADR-004 <em>is</em>
 * the SQL — {@code SELECT ... FOR UPDATE}, the allocation order, the CHECK constraints. A repository
 * that generated these statements would hide exactly the lines a reviewer has to read. This is the
 * one sanctioned deviation from the {@code @vaullet-io} template, whose dao layer is Spring Data JPA;
 * the layer boundary is unchanged.
 *
 * <p>The methods here take no decisions. Whether the balance is sufficient, whether a TTL is
 * allowed, which buckets to draw from — all of that is {@code LedgerService}'s, so that the rules
 * can be read in one place and unit-tested without a database. What the database <em>does</em> own
 * is stated as a constraint in {@code V1__ledger.sql} and enforced there, not here.
 *
 * <p>Row types are this package's own. They deliberately do not leak upward: the service maps them
 * into its domain records, so a change of column shape stops at this boundary.
 */
@Repository
public class LedgerRepository {

    private final JdbcTemplate jdbc;

    public LedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The account aggregate, as locked. */
    public record AccountRow(BigDecimal posted, BigDecimal held) {}

    /** A bucket with spendable room, in the order it should be drawn from. */
    public record BucketRow(UUID bucketId, String bucketType, BigDecimal available) {}

    /** A hold, as stored. {@code state} is the raw column value. */
    public record ReservationRow(UUID reservationId, UUID accountId, BigDecimal amount,
                                 String state, Instant expiresAt) {}

    /** How much of a hold a single bucket funds. */
    public record AllocationRow(UUID bucketId, String bucketType, BigDecimal amount) {}

    /**
     * Lock the account row and read its aggregate.
     *
     * <p>THE ONLY LOCK TAKEN ON THE MONEY PATH, and always taken first: reserve descends
     * account -> buckets while settle ascends bucket -> account, so a consistent order here is what
     * stops the two deadlocking against each other.
     *
     * <p>Returns empty rather than throwing — whether a missing account is an error is the caller's
     * decision, not this layer's.
     */
    public Optional<AccountRow> lockAccount(UUID accountId) {
        try {
            Map<String, Object> row = jdbc.queryForMap("""
                    SELECT posted_balance, held_total
                      FROM account_balances
                     WHERE account_id = ?
                       FOR UPDATE
                    """, accountId);
            return Optional.of(new AccountRow(
                    (BigDecimal) row.get("posted_balance"),
                    (BigDecimal) row.get("held_total")));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<ReservationRow> findByIdempotencyKey(String idempotencyKey) {
        return one("""
                SELECT reservation_id, account_id, amount, state, expires_at
                  FROM reservations
                 WHERE idempotency_key = ?
                """, idempotencyKey);
    }

    public Optional<ReservationRow> findReservation(UUID reservationId) {
        return one("""
                SELECT reservation_id, account_id, amount, state, expires_at
                  FROM reservations
                 WHERE reservation_id = ?
                """, reservationId);
    }

    /** The deployment's ceiling on a caller-supplied hold TTL. */
    public long maxHoldSeconds() {
        Long max = jdbc.queryForObject("SELECT max_hold_seconds FROM ledger_config", Long.class);
        if (max == null) {
            throw new IllegalStateException("ledger_config is empty — the service cannot bound a hold TTL");
        }
        return max;
    }

    /**
     * Buckets that can fund a hold, most-spendable-first.
     *
     * <p>DEBT is excluded in the WHERE clause, not in the caller's loop: it holds a POSITIVE amount
     * representing what the user owes (ADR-009), so a naive "any bucket with a balance" query would
     * let someone spend their own debt. Expired buckets are excluded for the same reason — the
     * sweeper may not have zeroed them yet.
     */
    public List<BucketRow> allocatableBuckets(UUID accountId) {
        return jdbc.query("""
                SELECT bucket_id, bucket_type, posted_balance - held_total AS available
                  FROM balance_buckets
                 WHERE account_id = ?
                   AND bucket_type <> 'DEBT'
                   AND (expires_at IS NULL OR expires_at > now())
                   AND posted_balance - held_total > 0
                 ORDER BY spend_priority, expires_at NULLS LAST
                """,
                (rs, i) -> new BucketRow(rs.getObject("bucket_id", UUID.class),
                        rs.getString("bucket_type"), rs.getBigDecimal("available")),
                accountId);
    }

    /**
     * Insert a hold and return the expiry the database computed.
     *
     * <p>The expiry is {@code now() + interval} evaluated server-side and read back rather than
     * calculated here: the database clock is the one every other statement compares against, and a
     * hold that expires by the application's clock is a hold that expires at the wrong moment.
     */
    public Instant insertReservation(UUID reservationId, UUID accountId, BigDecimal amount,
                                     String idempotencyKey, long ttlSeconds) {
        jdbc.update("""
                INSERT INTO reservations (reservation_id, account_id, amount, state, idempotency_key, expires_at)
                VALUES (?, ?, ?, 'HELD', ?, now() + make_interval(secs => ?))
                """, reservationId, accountId, amount, idempotencyKey, (double) ttlSeconds);

        Timestamp expiresAt = jdbc.queryForObject(
                "SELECT expires_at FROM reservations WHERE reservation_id = ?",
                Timestamp.class, reservationId);
        if (expiresAt == null) {
            throw new IllegalStateException("reservation " + reservationId + " vanished within its own transaction");
        }
        return expiresAt.toInstant();
    }

    public void insertAllocation(UUID reservationId, UUID bucketId, BigDecimal amount) {
        jdbc.update("INSERT INTO reservation_allocations (reservation_id, bucket_id, amount) VALUES (?, ?, ?)",
                reservationId, bucketId, amount);
    }

    /** Signed delta, so the same statement serves a hold and its release. */
    public void addBucketHold(UUID bucketId, BigDecimal delta) {
        jdbc.update("UPDATE balance_buckets SET held_total = held_total + ? WHERE bucket_id = ?",
                delta, bucketId);
    }

    /** Signed delta. {@code account_not_overdrawn} is the backstop if a caller gets this wrong. */
    public void addAccountHold(UUID accountId, BigDecimal delta) {
        jdbc.update("UPDATE account_balances SET held_total = held_total + ?, updated_at = now() WHERE account_id = ?",
                delta, accountId);
    }

    /**
     * Capture held money on a bucket: the hold becomes a spend.
     *
     * <p>Both columns move in ONE statement, and that is not a style choice.
     * {@code bucket_not_overdrawn} is {@code posted_balance - held_total >= 0}; decrementing
     * posted in one statement and held in the next leaves an intermediate row where 40 posted
     * still carries a 60 hold, and the constraint rejects it. The capture is a single UPDATE
     * because it is a single fact.
     */
    public void captureBucket(UUID bucketId, BigDecimal amount) {
        jdbc.update("""
                UPDATE balance_buckets
                   SET posted_balance = posted_balance - ?,
                       held_total     = held_total - ?
                 WHERE bucket_id = ?
                """, amount, amount, bucketId);
    }

    /** The account-level half of a capture. One statement, for the reason above. */
    public void captureAccount(UUID accountId, BigDecimal amount) {
        jdbc.update("""
                UPDATE account_balances
                   SET posted_balance = posted_balance - ?,
                       held_total     = held_total - ?,
                       updated_at     = now()
                 WHERE account_id = ?
                """, amount, amount, accountId);
    }

    /** One line of the journal. */
    public record JournalEntryRow(UUID entryId, UUID bucketId, String direction, BigDecimal amount,
                                  UUID reservationId, UUID transactionId) {}

    /**
     * Append a debit to the journal.
     *
     * <p>No update path exists, and none should: {@code ledger_entries} carries a RULE that turns
     * an UPDATE or DELETE into a no-op, because a correction to a ledger is a new entry rather than
     * an edit to an old one. The {@code (transaction_id, bucket_id, direction)} unique index is
     * what makes a redelivered event safe — a caller that posts the same transaction twice gets a
     * constraint violation, not a duplicated debit.
     */
    public void postDebit(UUID accountId, UUID bucketId, BigDecimal amount,
                          UUID reservationId, UUID transactionId) {
        jdbc.update("""
                INSERT INTO ledger_entries (entry_id, account_id, bucket_id, direction, amount,
                                            reservation_id, transaction_id)
                VALUES (?, ?, ?, 'DEBIT', ?, ?, ?)
                """, UUID.randomUUID(), accountId, bucketId, amount, reservationId, transactionId);
    }

    public List<JournalEntryRow> entriesFor(UUID reservationId) {
        return jdbc.query("""
                SELECT entry_id, bucket_id, direction, amount, reservation_id, transaction_id
                  FROM ledger_entries
                 WHERE reservation_id = ?
                 ORDER BY created_at, entry_id
                """,
                (rs, i) -> new JournalEntryRow(
                        rs.getObject("entry_id", UUID.class),
                        rs.getObject("bucket_id", UUID.class),
                        rs.getString("direction"),
                        rs.getBigDecimal("amount"),
                        rs.getObject("reservation_id", UUID.class),
                        rs.getObject("transaction_id", UUID.class)),
                reservationId);
    }

    public void updateState(UUID reservationId, String state) {
        jdbc.update("UPDATE reservations SET state = ? WHERE reservation_id = ?", state, reservationId);
    }

    public List<AllocationRow> allocationsOf(UUID reservationId) {
        return jdbc.query("""
                SELECT ra.bucket_id, b.bucket_type, ra.amount
                  FROM reservation_allocations ra
                  JOIN balance_buckets b ON b.bucket_id = ra.bucket_id
                 WHERE ra.reservation_id = ?
                """,
                (rs, i) -> new AllocationRow(rs.getObject("bucket_id", UUID.class),
                        rs.getString("bucket_type"), rs.getBigDecimal("amount")),
                reservationId);
    }

    /** Unlocked read of the account aggregate, for queries that take no money decision. */
    public Optional<AccountBalanceRow> accountBalance(UUID accountId) {
        try {
            Map<String, Object> row = jdbc.queryForMap("""
                    SELECT posted_balance, held_total, debt_total
                      FROM account_balances
                     WHERE account_id = ?
                    """, accountId);
            return Optional.of(new AccountBalanceRow(
                    (BigDecimal) row.get("posted_balance"),
                    (BigDecimal) row.get("held_total"),
                    (BigDecimal) row.get("debt_total")));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** Posted, held and owed for one account. */
    public record AccountBalanceRow(BigDecimal posted, BigDecimal held, BigDecimal debt) {}

    /**
     * What the account could actually withdraw, as opposed to what it could spend.
     *
     * <p>Summed over buckets rather than taken from the aggregate: withdrawability is a per-bucket
     * property (promotional money spends but does not withdraw), so the aggregate cannot answer it.
     */
    public BigDecimal withdrawable(UUID accountId) {
        BigDecimal sum = jdbc.queryForObject("""
                SELECT COALESCE(SUM(posted_balance - held_total), 0)
                  FROM balance_buckets
                 WHERE account_id = ? AND withdrawable
                   AND (expires_at IS NULL OR expires_at > now())
                """, BigDecimal.class, accountId);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    private Optional<ReservationRow> one(String sql, Object arg) {
        List<ReservationRow> rows = jdbc.query(sql,
                (rs, i) -> new ReservationRow(
                        rs.getObject("reservation_id", UUID.class),
                        rs.getObject("account_id", UUID.class),
                        rs.getBigDecimal("amount"),
                        rs.getString("state"),
                        rs.getTimestamp("expires_at").toInstant()),
                arg);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
