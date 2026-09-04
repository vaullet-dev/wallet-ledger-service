package io.vaullet.ledger.reservation.service;

import io.vaullet.ledger.reservation.dao.LedgerRepository;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Check-and-reserve, as one atomic transaction — ADR-004.
 *
 * <p>The whole design rests on two things a reader should be able to verify here without
 * trusting any prose: the account row is locked <em>first</em> on every path, and read and
 * write happen inside the same transaction. ADR-001 failed because neither was true — it
 * serialized a read while the write happened asynchronously in another service.
 *
 * <p>The transaction boundary lives here rather than in the repository, which could not compose
 * two writes atomically, or in a controller, which would hold a connection while serialising.
 */
@Service
public class LedgerService {

    /** ADR-004: the default authorization hold. A two-phase wager overrides it explicitly. */
    public static final Duration DEFAULT_HOLD = Duration.ofSeconds(300);

    private final LedgerRepository ledger;

    public LedgerService(LedgerRepository ledger) {
        this.ledger = ledger;
    }

    public record Allocation(UUID bucketId, String bucketType, BigDecimal amount) {}

    public record Reservation(UUID reservationId, BigDecimal amount, String state,
                              Instant expiresAt, List<Allocation> allocations) {}

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

        // (1) Lock the account before reading anything else. Everything below this line is
        // serialized against every other balance-changing operation on this account.
        var account = ledger.lockAccount(accountId)
                .orElseThrow(() -> new AccountNotFound("no account " + accountId));

        // (2) Idempotency. A retried reserve must return the existing hold, never place a second
        // one. Two racing requests with the same key are already serialized by the lock above,
        // so this read-then-insert is safe; the UNIQUE constraint is the backstop, not the plan.
        var existing = ledger.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            var row = existing.get();
            return new Reservation(row.reservationId(), row.amount(), row.state(), row.expiresAt(),
                    allocationsOf(row.reservationId()));
        }

        BigDecimal available = account.posted().subtract(account.held());
        if (available.compareTo(amount) < 0) {
            throw new InsufficientFunds("available " + available + " < requested " + amount);
        }

        long maxHold = ledger.maxHoldSeconds();
        Duration hold = ttl == null ? DEFAULT_HOLD : ttl;
        if (hold.getSeconds() > maxHold) {
            throw new HoldTtlTooLong(hold.getSeconds() + "s exceeds max_hold_seconds " + maxHold);
        }

        // (3) Allocate greedily across buckets, in the order the repository returned them —
        // spend_priority first, then soonest expiry, so money that would otherwise be lost is
        // spent before money that keeps.
        List<Allocation> allocations = new ArrayList<>();
        BigDecimal remaining = amount;
        for (var bucket : ledger.allocatableBuckets(accountId)) {
            if (remaining.signum() == 0) break;
            BigDecimal take = bucket.available().min(remaining);
            allocations.add(new Allocation(bucket.bucketId(), bucket.bucketType(), take));
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
        Instant expiresAt = ledger.insertReservation(
                reservationId, accountId, amount, idempotencyKey, hold.getSeconds());

        for (Allocation a : allocations) {
            ledger.insertAllocation(reservationId, a.bucketId(), a.amount());
            ledger.addBucketHold(a.bucketId(), a.amount());
        }
        ledger.addAccountHold(accountId, amount);

        return new Reservation(reservationId, amount, "HELD", expiresAt, allocations);
    }

    /**
     * Read a hold as it stands, with the buckets it draws from.
     *
     * <p>Serves {@code GET /v1/reservations/{id}}, which ADR-004 provides so a two-phase caller can
     * see what it is currently holding rather than inferring it from its own request.
     *
     * <p>No lock: this is a read, and a caller acting on the result is already accepting that the
     * state may have moved on. The only place the balance invariant is decided is {@link #reserve}.
     *
     * <p>Unknown ids surface as {@code EmptyResultDataAccessException}, matching {@code release} and
     * {@code balance}. See the note there — it wants to be a typed domain error.
     */
    @Transactional(readOnly = true)
    public Reservation find(UUID reservationId) {
        var reservation = ledger.findReservation(reservationId)
                .orElseThrow(() -> new EmptyResultDataAccessException(1));
        return new Reservation(
                reservation.reservationId(),
                reservation.amount(),
                reservation.state(),
                reservation.expiresAt(),
                allocationsOf(reservationId));
    }

    /**
     * Capture a hold: the reserved money is spent.
     *
     * @param transactionId the business transaction this capture belongs to. Supplied by the
     *                      caller because it is the caller's event that is being settled.
     */
    @Transactional
    public void settle(UUID reservationId, UUID transactionId) {
        var reservation = ledger.findReservation(reservationId)
                .orElseThrow(() -> new EmptyResultDataAccessException(1));

        // Account first, always. account_balances is the only row locked on the money path, so
        // this one statement serializes settlement against every concurrent reserve and release.
        ledger.lockAccount(reservation.accountId());
        if (!"HELD".equals(reservation.state())) return;   // already settled, released or expired

        // One debit per bucket the hold drew from, not one for the reservation total. The journal
        // has to say which money was spent, not merely how much: a 80 stake funded by 60 of bonus
        // and 20 of cash is two different obligations, and a single aggregate entry loses the
        // distinction that withdrawability and wagering both depend on.
        for (var a : ledger.allocationsOf(reservationId)) {
            ledger.postDebit(reservation.accountId(), a.bucketId(), a.amount(), reservationId, transactionId);
            ledger.captureBucket(a.bucketId(), a.amount());
        }
        ledger.captureAccount(reservation.accountId(), reservation.amount());
        ledger.updateState(reservationId, "SETTLED");
    }

    /** Release a hold. No ledger entry — money that never moved leaves no journal record. */
    @Transactional
    public void release(UUID reservationId) {
        // Unknown reservation surfaces as EmptyResultDataAccessException, as it did before the
        // dao split. It wants to be a typed domain error, but that is a behaviour change and
        // belongs with the common/error work, not with a refactor the tests are guarding.
        var reservation = ledger.findReservation(reservationId)
                .orElseThrow(() -> new EmptyResultDataAccessException(1));

        // Account first, always — even here, where it looks unnecessary.
        ledger.lockAccount(reservation.accountId());
        if (!"HELD".equals(reservation.state())) return;   // already settled, released or expired

        for (var a : ledger.allocationsOf(reservationId)) {
            ledger.addBucketHold(a.bucketId(), a.amount().negate());
        }
        ledger.addAccountHold(reservation.accountId(), reservation.amount().negate());
        ledger.updateState(reservationId, "RELEASED");
    }

    @Transactional(readOnly = true)
    public Balance balance(UUID accountId) {
        // As above: unknown account surfaces as EmptyResultDataAccessException here, while
        // reserve() maps it to AccountNotFound. Making the two agree is a behaviour change.
        var account = ledger.accountBalance(accountId)
                .orElseThrow(() -> new EmptyResultDataAccessException(1));
        return new Balance(
                account.posted(),
                account.held(),
                account.posted().subtract(account.held()),
                ledger.withdrawable(accountId),
                account.debt());
    }

    private List<Allocation> allocationsOf(UUID reservationId) {
        return ledger.allocationsOf(reservationId).stream()
                .map(a -> new Allocation(a.bucketId(), a.bucketType(), a.amount()))
                .toList();
    }
}
