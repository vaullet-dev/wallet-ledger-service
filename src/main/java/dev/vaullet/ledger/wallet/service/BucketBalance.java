package dev.vaullet.ledger.wallet.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One ADR-004 bucket inside a wallet.
 *
 * @param available what this bucket can fund now: its {@code posted - held}, or zero for DEBT, for
 *     a grant past its expiry, and for every bucket of a wallet that is not ACTIVE. A wallet's
 *     {@code available} is its buckets' added up.
 */
public record BucketBalance(
        UUID bucketId,
        String bucketType,
        BigDecimal posted,
        BigDecimal held,
        BigDecimal available,
        boolean withdrawable,
        BigDecimal wageringRemaining,
        @Nullable Instant expiresAt) {}
