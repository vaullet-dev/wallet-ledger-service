package dev.vaullet.ledger.wallet.service;

import dev.vaullet.ledger.common.error.exception.InvalidWalletSearchException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Where a wallet search resumes: the {@code (created_at, wallet_id)} of the last wallet on the
 * previous page, which is the key the search orders by (keyset pagination, ADR-011 §8).
 *
 * <p>Opaque to callers, as base64url of {@code v1:<created_at in epoch microseconds>:<wallet_id>},
 * so the format can change behind the version prefix. Microseconds because that is TIMESTAMPTZ's
 * precision: a coarser cursor would repeat or skip wallets created within one millisecond.
 */
record WalletCursor(Instant createdAt, UUID walletId) {

    private static final String VERSION = "v1";
    private static final long MICROS_PER_SECOND = 1_000_000L;

    /**
     * No issued cursor falls outside these. A forged one that did would reach the database as a
     * timestamp the driver or PostgreSQL rejects: a 500 instead of the 400 it deserves.
     */
    private static final Instant EARLIEST = Instant.EPOCH;
    private static final Instant LATEST = Instant.parse("9999-12-31T23:59:59.999999Z");

    static WalletCursor after(Wallet last) {
        return new WalletCursor(last.createdAt(), last.walletId());
    }

    String encode() {
        long micros = createdAt.getEpochSecond() * MICROS_PER_SECOND + createdAt.getNano() / 1_000;
        String raw = VERSION + ":" + micros + ":" + walletId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }

    /** @throws InvalidWalletSearchException for anything {@link #encode()} could not have produced */
    static WalletCursor decode(String cursor) {
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII)
                    .split(":", -1);
            if (parts.length == 3 && VERSION.equals(parts[0])) {
                long micros = Long.parseLong(parts[1]);
                Instant createdAt = Instant.ofEpochSecond(
                        Math.floorDiv(micros, MICROS_PER_SECOND), Math.floorMod(micros, MICROS_PER_SECOND) * 1_000);
                UUID walletId = UUID.fromString(parts[2]);
                if (!createdAt.isBefore(EARLIEST) && !createdAt.isAfter(LATEST)
                        && walletId.toString().equals(parts[2])) {
                    return new WalletCursor(createdAt, walletId);
                }
            }
        } catch (IllegalArgumentException e) {
            // Not base64url, not a number, not a UUID: all the same to the caller.
        }
        throw new InvalidWalletSearchException(
                "cursor is not one this service issued: pass the previous page's next cursor unchanged");
    }
}
