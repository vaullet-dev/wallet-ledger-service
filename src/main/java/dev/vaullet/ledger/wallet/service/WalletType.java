package dev.vaullet.ledger.wallet.service;

/**
 * What kind of money a wallet holds. An account has at most one wallet of each type.
 *
 * <p>A wallet groups ADR-004's balance buckets; it does not replace them:
 *
 * <ul>
 *   <li>{@link #REAL} holds the account's {@code CASH} bucket, and its {@code DEBT} bucket once
 *       ADR-009 creates one. Real money: deposited, spendable, withdrawable.
 *   <li>{@link #BONUS} holds the promotional buckets ({@code BONUS}, {@code LOYALTY},
 *       {@code REFERRAL}), one per grant, each with its own wagering and expiry. Spendable, never
 *       withdrawable while wagering is outstanding.
 * </ul>
 *
 * <p>More types are expected; adding one is a migration (the {@code wallets.wallet_type} CHECK)
 * plus a line in {@link #holds(String)}.
 */
public enum WalletType {
    REAL,
    BONUS;

    /** Whether a bucket of {@code bucketType} belongs in a wallet of this type. */
    public boolean holds(String bucketType) {
        return this == forBucketType(bucketType);
    }

    /** The wallet a bucket of {@code bucketType} lives in. */
    public static WalletType forBucketType(String bucketType) {
        return switch (bucketType) {
            case "CASH", "DEBT" -> REAL;
            case "BONUS", "LOYALTY", "REFERRAL" -> BONUS;
            default -> throw new IllegalArgumentException("Unknown bucket type " + bucketType);
        };
    }
}
