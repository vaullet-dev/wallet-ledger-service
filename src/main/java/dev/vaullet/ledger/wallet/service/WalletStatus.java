package dev.vaullet.ledger.wallet.service;

/**
 * A wallet's lifecycle.
 *
 * <ul>
 *   <li>{@link #ACTIVE}: its money can be spent.
 *   <li>{@link #FROZEN}: its money stays where it is and cannot be spent; reservations allocate
 *       around it. Reversible.
 *   <li>{@link #CLOSED}: terminal, and only reachable at a zero balance with nothing held. The row
 *       and its buckets stay, because ledger entries point at them.
 * </ul>
 */
public enum WalletStatus {
    ACTIVE,
    FROZEN,
    CLOSED
}
