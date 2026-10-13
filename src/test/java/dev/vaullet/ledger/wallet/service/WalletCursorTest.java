package dev.vaullet.ledger.wallet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vaullet.common.error.CommonErrorType;
import dev.vaullet.ledger.common.error.exception.InvalidWalletSearchException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The search cursor: opaque to callers, exact to the microsecond for the service. */
class WalletCursorTest {

    private static final UUID WALLET = UUID.fromString("f0e1d2c3-b4a5-4697-8879-6a5b4c3d2e1f");

    @Test
    @DisplayName("round-trips a position to the microsecond, as URL-safe text")
    void roundTrips() {
        var cursor = new WalletCursor(Instant.parse("2026-10-13T10:00:00.123456Z"), WALLET);

        String encoded = cursor.encode();

        assertThat(encoded).matches("[A-Za-z0-9_-]+");
        assertThat(WalletCursor.decode(encoded)).isEqualTo(cursor);
        assertThat(new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.US_ASCII))
                .isEqualTo("v1:1791885600123456:" + WALLET);
    }

    @Test
    @DisplayName("resumes after the last wallet of the page")
    void resumesAfterTheLastWallet() {
        var last = new Wallet(WALLET, UUID.randomUUID(), WalletType.REAL, WalletStatus.ACTIVE,
                Instant.parse("2026-10-13T10:00:00.000001Z"), Instant.parse("2026-10-13T11:00:00Z"), null);

        assertThat(WalletCursor.after(last)).isEqualTo(new WalletCursor(last.createdAt(), WALLET));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "not base64url!",
        "djE6MTc2MDM0OTYwMDEyMzQ1Njpm+A==",           // standard base64, not base64url
        "v2:1791885600123456:f0e1d2c3-b4a5-4697-8879-6a5b4c3d2e1f",
        "v1:1791885600123456",
        "v1:soon:f0e1d2c3-b4a5-4697-8879-6a5b4c3d2e1f",
        "v1:-1:f0e1d2c3-b4a5-4697-8879-6a5b4c3d2e1f",
        "v1:1791885600123456:F0E1D2C3-B4A5-4697-8879-6A5B4C3D2E1F",
        "v1:1791885600123456:f0e1d2c3",
        "v1:1791885600123456:f0e1d2c3-b4a5-4697-8879-6a5b4c3d2e1f:more",
        "v1:999999999999999999:f0e1d2c3-b4a5-4697-8879-6a5b4c3d2e1f"
    })
    @DisplayName("refuses anything encode() could not have produced, as VALIDATION_FAILED")
    void refusesForeignCursors(String raw) {
        String cursor = raw.startsWith("v") ? encoded(raw) : raw;

        assertThatThrownBy(() -> WalletCursor.decode(cursor))
                .isInstanceOfSatisfying(InvalidWalletSearchException.class,
                        e -> assertThat(e.errorType()).isEqualTo(CommonErrorType.VALIDATION_FAILED));
    }

    private static String encoded(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }
}
