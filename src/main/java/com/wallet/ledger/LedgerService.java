package com.wallet.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Check-and-reserve, as one atomic transaction — ADR-004.
 *
 * <p>The whole design rests on two things a reader should be able to verify here without
 * trusting any prose: the account row is locked <em>first</em> on every path, and read and
 * write happen inside the same transaction. ADR-001 failed because neither was true — it
 * serialized a read while the write happened asynchronously in another service.
 */
@Service
public class LedgerService {

    /** ADR-004: the default authorization hold. A two-phase wager overrides it explicitly. */
    public static final Duration DEFAULT_HOLD = Duration.ofSeconds(300);

    private final JdbcTemplate jdbc;

    public LedgerService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Allocation(UUID bucketId, String bucketType, BigDecimal amount) {}

    public record Reservation(UUID reservationId, BigDecimal amount, String state,
                              java.time.Instant expiresAt, List<Allocation> allocations) {}

    public record Balance(BigDecimal posted, BigDecimal held, BigDecimal available,
                          BigDecimal withdrawable, BigDecimal debt) {}

    public static class InsufficientFunds extends RuntimeException {
        public InsufficientFunds(String m) { super(m); }
    }

    public static class HoldTtlTooLong extends RuntimeException {
        public HoldTtlTooLong(String m) { super(m); }
    }

    public static class AccountNotFound extends RuntimeException {
        public AccountNotFound(String m) { super(m); }
    }

    /**
     * Atomically check available balance and place a hold.
     *
     * @param ttl how long the hold survives uncaptured. Null means {@link #DEFAULT_HOLD}.
     *            The caller sets this because only the caller knows its settlement horizon:
     *            a purchase resolves in milliseconds, a match-winner market in three days.
     */
    @Transactional
    public Reservation reserve(UUID accountId, BigDecimal amount, String idempotencyKey, Duration ttl) {

        // (1) The account row. THE ONLY LOCK TAKEN, and always taken first — reserve descends
        // account -> buckets while settle ascends bucket -> account, so a consistent order here
        // is what stops the two deadlocking against each other on the money path.
        Map<String, Object> acct;
        try {
            acct = jdbc.queryForMap("""
                SELECT posted_balance, held_total
                  FROM account_balances
                 WHERE account_id = ?
                   FOR UPDATE
                """, accountId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new AccountNotFound("no account " + accountId);
        }

        // (2) Idempotency. A retried reserve must return the existing hold, never place a second
        // one. Two racing requests with the same key are already serialized by the lock above,
        // so this read-then-insert is safe; the UNIQUE constraint is the backstop, not the plan.
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT reservation_id, amount, state, expires_at FROM reservations WHERE idempotency_key = ?",
                idempotencyKey);
        if (!existing.isEmpty()) {
            UUID id = (UUID) existing.get(0).get("reservation_id");
            return new Reservation(id,
                    (BigDecimal) existing.get(0).get("amount"),
                    (String) existing.get(0).get("state"),
                    ((java.sql.Timestamp) existing.get(0).get("expires_at")).toInstant(),
                    allocationsOf(id));
        }

        BigDecimal posted = (BigDecimal) acct.get("posted_balance");
        BigDecimal held = (BigDecimal) acct.get("held_total");
        BigDecimal available = posted.subtract(held);
        if (available.compareTo(amount) < 0) {
            throw new InsufficientFunds("available " + available + " < requested " + amount);
        }

        long maxHold = jdbc.queryForObject("SELECT max_hold_seconds FROM ledger_config", Long.class);
        Duration hold = ttl == null ? DEFAULT_HOLD : ttl;
        if (hold.getSeconds() > maxHold) {
            throw new HoldTtlTooLong(hold.getSeconds() + "s exceeds max_hold_seconds " + maxHold);
        }

        // (3) Allocate greedily across buckets. DEBT is excluded: it holds a POSITIVE amount
        // representing what the user owes (ADR-009), so a naive "any bucket with a balance"
        // query would let someone spend their own debt.
        List<Map<String, Object>> buckets = jdbc.queryForList("""
                SELECT bucket_id, bucket_type, posted_balance - held_total AS available
                  FROM balance_buckets
                 WHERE account_id = ?
                   AND bucket_type <> 'DEBT'
                   AND (expires_at IS NULL OR expires_at > now())
                   AND posted_balance - held_total > 0
                 ORDER BY spend_priority, expires_at NULLS LAST
                """, accountId);

        List<Allocation> allocations = new ArrayList<>();
        BigDecimal remaining = amount;
        for (Map<String, Object> b : buckets) {
            if (remaining.signum() == 0) break;
            BigDecimal take = ((BigDecimal) b.get("available")).min(remaining);
            allocations.add(new Allocation((UUID) b.get("bucket_id"), (String) b.get("bucket_type"), take));
            remaining = remaining.subtract(take);
        }

        // The aggregate said yes but the buckets cannot cover it. Reachable when a bucket has
        // expired and the sweeper has not yet zeroed it, so posted_balance is briefly stale.
        // Rejecting is correct: never hold money no bucket can fund.
        if (remaining.signum() > 0) {
            throw new InsufficientFunds("allocatable balance short by " + remaining
                    + " (expired buckets not yet swept)");
        }

        UUID reservationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO reservations (reservation_id, account_id, amount, state, idempotency_key, expires_at)
                VALUES (?, ?, ?, 'HELD', ?, now() + make_interval(secs => ?))
                """, reservationId, accountId, amount, idempotencyKey, (double) hold.getSeconds());

        for (Allocation a : allocations) {
            jdbc.update("INSERT INTO reservation_allocations (reservation_id, bucket_id, amount) VALUES (?, ?, ?)",
                    reservationId, a.bucketId(), a.amount());
            jdbc.update("UPDATE balance_buckets SET held_total = held_total + ? WHERE bucket_id = ?",
                    a.amount(), a.bucketId());
        }
        jdbc.update("UPDATE account_balances SET held_total = held_total + ?, updated_at = now() WHERE account_id = ?",
                amount, accountId);

        java.time.Instant expiresAt = jdbc.queryForObject(
                "SELECT expires_at FROM reservations WHERE reservation_id = ?",
                java.sql.Timestamp.class, reservationId).toInstant();

        return new Reservation(reservationId, amount, "HELD", expiresAt, allocations);
    }

    /** Release a hold. No ledger entry — money that never moved leaves no journal record. */
    @Transactional
    public void release(UUID reservationId) {
        Map<String, Object> r = jdbc.queryForMap(
                "SELECT account_id, amount, state FROM reservations WHERE reservation_id = ?", reservationId);
        UUID accountId = (UUID) r.get("account_id");

        // Account first, always — even here, where it looks unnecessary.
        jdbc.queryForMap("SELECT account_id FROM account_balances WHERE account_id = ? FOR UPDATE", accountId);
        if (!"HELD".equals(r.get("state"))) return;   // already settled, released or expired

        for (Allocation a : allocationsOf(reservationId)) {
            jdbc.update("UPDATE balance_buckets SET held_total = held_total - ? WHERE bucket_id = ?",
                    a.amount(), a.bucketId());
        }
        jdbc.update("UPDATE account_balances SET held_total = held_total - ?, updated_at = now() WHERE account_id = ?",
                r.get("amount"), accountId);
        jdbc.update("UPDATE reservations SET state = 'RELEASED' WHERE reservation_id = ?", reservationId);
    }

    @Transactional(readOnly = true)
    public Balance balance(UUID accountId) {
        Map<String, Object> a = jdbc.queryForMap(
                "SELECT posted_balance, held_total, debt_total FROM account_balances WHERE account_id = ?", accountId);
        BigDecimal posted = (BigDecimal) a.get("posted_balance");
        BigDecimal held = (BigDecimal) a.get("held_total");
        BigDecimal withdrawable = jdbc.queryForObject("""
                SELECT COALESCE(SUM(posted_balance - held_total), 0)
                  FROM balance_buckets
                 WHERE account_id = ? AND withdrawable
                   AND (expires_at IS NULL OR expires_at > now())
                """, BigDecimal.class, accountId);
        return new Balance(posted, held, posted.subtract(held), withdrawable, (BigDecimal) a.get("debt_total"));
    }

    private List<Allocation> allocationsOf(UUID reservationId) {
        return jdbc.query("""
                SELECT ra.bucket_id, b.bucket_type, ra.amount
                  FROM reservation_allocations ra
                  JOIN balance_buckets b ON b.bucket_id = ra.bucket_id
                 WHERE ra.reservation_id = ?
                """,
                (rs, i) -> new Allocation(rs.getObject("bucket_id", UUID.class),
                        rs.getString("bucket_type"), rs.getBigDecimal("amount")),
                reservationId);
    }
}
