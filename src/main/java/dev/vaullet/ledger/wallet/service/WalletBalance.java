package dev.vaullet.ledger.wallet.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A wallet's money, summed over its buckets. Advisory, like every balance read (ADR-004): stale the
 * moment it is returned.
 *
 * <ul>
 *   <li>{@code posted}, {@code held}: over the fundable buckets (DEBT excluded), whatever the status.
 *   <li>{@code available}: what can be spent now, its buckets' {@code available} added up:
 *       {@code posted - held} less any grant past its expiry. Zero unless the wallet is ACTIVE.
 *   <li>{@code withdrawable}: the available part of withdrawable buckets. Zero unless ACTIVE.
 *   <li>{@code debt}: what the user owes (ADR-009 DEBT buckets), never spendable.
 * </ul>
 *
 * @param currency the deployment's currency, {@code ledger_config.currency}
 * @param buckets every bucket in the wallet, CASH first, then by spend priority
 */
public record WalletBalance(
        UUID walletId,
        UUID accountId,
        WalletType type,
        WalletStatus status,
        String currency,
        BigDecimal posted,
        BigDecimal held,
        BigDecimal available,
        BigDecimal withdrawable,
        BigDecimal debt,
        List<BucketBalance> buckets) {}
