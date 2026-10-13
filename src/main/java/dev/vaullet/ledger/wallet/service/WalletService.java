package dev.vaullet.ledger.wallet.service;

import dev.vaullet.common.error.exception.ResourceNotFoundException;
import dev.vaullet.ledger.common.error.exception.InvalidWalletSearchException;
import dev.vaullet.ledger.common.error.exception.WalletClosedException;
import dev.vaullet.ledger.common.error.exception.WalletNotEmptyException;
import dev.vaullet.ledger.config.dao.LedgerConfigRepository;
import dev.vaullet.ledger.wallet.dao.WalletRepository;
import dev.vaullet.ledger.wallet.dao.WalletRepository.BucketRow;
import dev.vaullet.ledger.wallet.dao.WalletRepository.WalletRow;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wallets: open, read, freeze, close, and their balances.
 *
 * <p>A wallet groups ADR-004's buckets and leaves its rules alone. Every write here takes the
 * account row lock first, as ADR-004 requires of anything that touches an account's money, so a
 * freeze or a close serializes against the reserve path instead of racing it. The rules live here
 * and the statements in {@link WalletRepository}, as in {@code LedgerService}.
 */
@Service
public class WalletService {

    private static final String CASH = "CASH";
    private static final String DEBT = "DEBT";

    /**
     * A REAL wallet's CASH bucket spends last. Grants use lower numbers, so promotional money goes
     * first and nobody forfeits a bonus by spending cash (ADR-004, "Spend priority").
     */
    static final int CASH_SPEND_PRIORITY = 100;

    /** Every amount comes back at NUMERIC(20,4)'s four places, an empty sum included. */
    private static final BigDecimal ZERO = new BigDecimal("0.0000");

    private final WalletRepository wallets;
    private final LedgerConfigRepository config;

    public WalletService(WalletRepository wallets, LedgerConfigRepository config) {
        this.wallets = wallets;
        this.config = config;
    }

    /** The result of {@link #open}: the wallet, and whether this call created it. */
    public record Opened(Wallet wallet, boolean created) {}

    /**
     * Opens the account's wallet of {@code type}, opening the account in the ledger too if this is
     * its first wallet. A REAL wallet gets its CASH bucket at once; a BONUS wallet starts with no
     * buckets, which grants add later.
     *
     * <p>Repeating the request is safe: an ACTIVE or FROZEN wallet of that type is returned as it
     * is, with {@code created = false}.
     *
     * @throws WalletClosedException the account's wallet of this type is closed, and closing is
     *     terminal
     */
    @Transactional
    public Opened open(UUID accountId, WalletType type) {
        // ADR-004's lock order holds here too: the account row before any wallet or bucket row. This
        // can be the account's first wallet, so the row is inserted if absent and then locked; two
        // first opens for one account both end up queued on it.
        wallets.insertAccountIfAbsent(accountId);
        wallets.lockAccount(accountId);

        // Under that lock a repeated open meets the wallet an earlier one created, and the conflict
        // on (account_id, wallet_type) is how it finds out.
        boolean created = wallets.insertIfAbsent(UUID.randomUUID(), accountId, type.name());
        Wallet wallet = wallets.findByAccountAndType(accountId, type.name())
                .map(WalletService::toWallet)
                .orElseThrow(() -> new IllegalStateException("wallet vanished within its own transaction"));
        if (!created) {
            if (wallet.status() == WalletStatus.CLOSED) {
                throw new WalletClosedException(wallet.walletId());
            }
            return new Opened(wallet, false);
        }
        // CASH is one bucket per account and exists from the start (ADR-004); promotional money is a
        // bucket per grant, so there is nothing to create for it yet.
        if (type.holds(CASH)) {
            wallets.insertBucket(UUID.randomUUID(), accountId, wallet.walletId(), CASH, true, CASH_SPEND_PRIORITY);
        }
        return new Opened(wallet, true);
    }

    /** @throws ResourceNotFoundException no such wallet */
    @Transactional(readOnly = true)
    public Wallet get(UUID walletId) {
        return toWallet(found(walletId));
    }

    /**
     * Wallets matching the filters, oldest first, a page at a time.
     *
     * @throws InvalidWalletSearchException ({@code VALIDATION_FAILED}) for a cursor this service did
     *     not issue, or a limit outside 1 to {@link WalletSearch#MAX_LIMIT}
     */
    @Transactional(readOnly = true)
    public WalletPage search(WalletSearch search) {
        int limit = search.limit();
        if (limit < 1 || limit > WalletSearch.MAX_LIMIT) {
            throw new InvalidWalletSearchException("limit must be between 1 and " + WalletSearch.MAX_LIMIT);
        }
        @Nullable String cursor = search.cursor();
        @Nullable WalletCursor after = cursor == null ? null : WalletCursor.decode(cursor);
        // One more than the page holds: whether it comes back is what says there is a next page.
        List<Wallet> found = wallets.search(
                        search.accountId(),
                        nameOf(search.type()),
                        nameOf(search.status()),
                        after == null ? null : after.createdAt(),
                        after == null ? null : after.walletId(),
                        limit + 1)
                .stream()
                .map(WalletService::toWallet)
                .toList();
        if (found.size() <= limit) {
            return new WalletPage(found, null);
        }
        List<Wallet> page = found.subList(0, limit);
        return new WalletPage(page, WalletCursor.after(page.getLast()).encode());
    }

    /**
     * Freezes or unfreezes. Setting the status a wallet already has changes nothing.
     *
     * @param status {@link WalletStatus#ACTIVE} or {@link WalletStatus#FROZEN}; closing is
     *     {@link #close}
     * @throws ResourceNotFoundException no such wallet
     * @throws WalletClosedException the wallet is closed
     * @throws IllegalArgumentException {@code status} is CLOSED
     */
    @Transactional
    public Wallet changeStatus(UUID walletId, WalletStatus status) {
        if (status == WalletStatus.CLOSED) {
            throw new IllegalArgumentException("A wallet is closed by close(), which checks that it is empty");
        }
        Wallet wallet = lockedWallet(walletId);
        if (wallet.status() == WalletStatus.CLOSED) {
            throw new WalletClosedException(walletId);
        }
        if (wallet.status() == status) {
            return wallet;   // nothing changes, updated_at included
        }
        wallets.updateStatus(walletId, status.name());
        return toWallet(found(walletId));
    }

    /**
     * Closes the wallet. Closing a closed wallet changes nothing.
     *
     * @throws ResourceNotFoundException no such wallet
     * @throws WalletNotEmptyException it has a posted balance, a hold or a debt
     */
    @Transactional
    public void close(UUID walletId) {
        Wallet wallet = lockedWallet(walletId);
        if (wallet.status() == WalletStatus.CLOSED) {
            return;
        }
        // Nothing can post to or hold against these buckets while the account row is locked, so the
        // wallet found empty here is still empty when it is closed.
        Sums money = Sums.of(wallets.bucketsOf(walletId));
        if (money.posted().signum() != 0 || money.held().signum() != 0 || money.debt().signum() != 0) {
            throw new WalletNotEmptyException(walletId, money.posted(), money.held(), money.debt());
        }
        wallets.close(walletId);
    }

    /** @throws ResourceNotFoundException no such wallet */
    @Transactional(readOnly = true)
    public WalletBalance balance(UUID walletId) {
        Wallet wallet = toWallet(found(walletId));
        return balanceOf(wallet, wallets.bucketsOf(walletId), currency());
    }

    /** Every wallet of the account with its balance, REAL first; empty for an unknown account. */
    @Transactional(readOnly = true)
    public List<WalletBalance> balancesOf(UUID accountId) {
        List<Wallet> owned = wallets.findByAccount(accountId).stream()
                .map(WalletService::toWallet)
                .sorted(Comparator.comparing(Wallet::type))   // WalletType's declaration order: REAL first
                .toList();
        if (owned.isEmpty()) {
            return List.of();
        }
        String currency = currency();
        return owned.stream()
                .map(wallet -> balanceOf(wallet, wallets.bucketsOf(wallet.walletId()), currency))
                .toList();
    }

    /** The deployment's currency, {@code ledger_config.currency}. */
    public String currency() {
        return config.find()
                .map(LedgerConfigRepository.LedgerConfigRow::currency)
                .orElseThrow(() -> new IllegalStateException("ledger_config is empty: the ledger has no currency"));
    }

    /**
     * The wallet as it stands once its account row is locked (ADR-004: the account first). The read
     * before the lock only finds which account to lock; it locks nothing, and the status it saw may
     * be stale by the time the lock is held.
     */
    private Wallet lockedWallet(UUID walletId) {
        wallets.lockAccount(found(walletId).accountId());
        return toWallet(found(walletId));
    }

    private WalletRow found(UUID walletId) {
        return wallets.find(walletId).orElseThrow(() -> new ResourceNotFoundException("Wallet", walletId));
    }

    private static WalletBalance balanceOf(Wallet wallet, List<BucketRow> rows, String currency) {
        // A wallet that is not ACTIVE keeps its money posted, held and owed, but none of it can be
        // spent or withdrawn: the reserve path skips its buckets (LedgerRepository.allocatableBuckets).
        boolean active = wallet.status() == WalletStatus.ACTIVE;
        List<BucketBalance> buckets = rows.stream().map(row -> toBucket(row, active)).toList();
        Sums sums = Sums.of(rows);
        // The wallet's figures are its buckets' added up, so the two can never disagree.
        BigDecimal available = ZERO;
        BigDecimal withdrawable = ZERO;
        for (BucketBalance bucket : buckets) {
            available = available.add(bucket.available());
            if (bucket.withdrawable()) {
                withdrawable = withdrawable.add(bucket.available());
            }
        }
        return new WalletBalance(
                wallet.walletId(),
                wallet.accountId(),
                wallet.type(),
                wallet.status(),
                currency,
                sums.posted(),
                sums.held(),
                available,
                withdrawable,
                sums.debt(),
                buckets);
    }

    /**
     * What is in a wallet, summed over its buckets, whatever its status: what {@link #close} needs at
     * zero, and what a balance reports as posted, held and owed.
     */
    private record Sums(BigDecimal posted, BigDecimal held, BigDecimal debt) {

        static Sums of(List<BucketRow> buckets) {
            BigDecimal posted = ZERO;
            BigDecimal held = ZERO;
            BigDecimal debt = ZERO;
            for (BucketRow bucket : buckets) {
                if (DEBT.equals(bucket.bucketType())) {
                    // What the user owes (ADR-009): reported, never a funding source (ADR-004).
                    debt = debt.add(bucket.posted());
                } else {
                    posted = posted.add(bucket.posted());
                    held = held.add(bucket.held());
                }
            }
            return new Sums(posted, held, debt);
        }
    }

    /**
     * What a bucket can fund now, by the rules {@code LedgerRepository.allocatableBuckets} spends
     * by: nothing from DEBT, which is owed rather than held; nothing from a grant past its expiry,
     * whose money waits for the sweeper to forfeit it; and nothing at all while the wallet is not
     * ACTIVE. Every "available" in a balance is a sum of these, so a bucket, its wallet and the
     * account ({@code LedgerRepository.available}) always add up.
     */
    private static BigDecimal spendable(BucketRow bucket, boolean active) {
        if (!active || DEBT.equals(bucket.bucketType()) || bucket.expired()) {
            return ZERO;
        }
        return bucket.posted().subtract(bucket.held());
    }

    private static Wallet toWallet(WalletRow row) {
        return new Wallet(
                row.walletId(),
                row.accountId(),
                WalletType.valueOf(row.type()),
                WalletStatus.valueOf(row.status()),
                row.createdAt(),
                row.updatedAt(),
                row.closedAt());
    }

    private static BucketBalance toBucket(BucketRow row, boolean walletActive) {
        return new BucketBalance(
                row.bucketId(),
                row.bucketType(),
                row.posted(),
                row.held(),
                spendable(row, walletActive),
                row.withdrawable(),
                row.wageringRemaining(),
                row.expiresAt());
    }

    private static @Nullable String nameOf(@Nullable Enum<?> value) {
        return value == null ? null : value.name();
    }
}
