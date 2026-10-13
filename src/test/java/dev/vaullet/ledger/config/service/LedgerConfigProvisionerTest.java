package dev.vaullet.ledger.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vaullet.ledger.config.dao.LedgerConfigRepository;
import dev.vaullet.ledger.config.dao.LedgerConfigRepository.LedgerConfigRow;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The provisioning rules, without a database: when the row is written, from what, and every way a
 * start is refused. {@code LedgerConfigProvisionerIT} runs the same against PostgreSQL.
 */
class LedgerConfigProvisionerTest {

    private final LedgerConfigRepository config = mock(LedgerConfigRepository.class);

    @Test
    @DisplayName("an empty ledger is provisioned with ISO 4217's minor units unless told otherwise")
    void provisionsAnEmptyLedger() {
        when(config.find()).thenReturn(Optional.empty()).thenReturn(Optional.of(new LedgerConfigRow("JPY", 0)));
        when(config.insertIfAbsent("JPY", 0)).thenReturn(true);

        provisioner("JPY", null).provision();

        verify(config).insertIfAbsent("JPY", 0);
    }

    @Test
    @DisplayName("configured minor units are what gets written")
    void configuredMinorUnitsWin() {
        when(config.find()).thenReturn(Optional.empty()).thenReturn(Optional.of(new LedgerConfigRow("EUR", 4)));

        provisioner("EUR", 4).provision();

        verify(config).insertIfAbsent("EUR", 4);
    }

    @Test
    @DisplayName("an empty ledger with no currency configured refuses to start, and writes nothing")
    void noCurrencyNoStart() {
        when(config.find()).thenReturn(Optional.empty());

        for (String missing : new String[] {null, "", "  "}) {
            assertThatThrownBy(() -> provisioner(missing, null).provision())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("LEDGER_CURRENCY");
        }
        verify(config, never()).insertIfAbsent(anyString(), anyInt());
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
        "EURO, null, not an ISO 4217",
        "eur,  null, not an ISO 4217",
        "EUR,  5,    between 0 and 4",
        "EUR,  -1,   between 0 and 4",
        "XAU,  null, between 0 and 4"      // gold has no minor unit in ISO 4217, so it has to be configured
    })
    @DisplayName("a currency or minor unit the ledger cannot hold refuses to start")
    void refusesWhatItCannotHold(String currency, @Nullable Integer minorUnits, String reason) {
        when(config.find()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> provisioner(currency, minorUnits).provision())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(reason);
        verify(config, never()).insertIfAbsent(anyString(), anyInt());
    }

    @Test
    @DisplayName("a provisioned ledger starts with the same currency configured, or with none")
    void aProvisionedLedgerOnlyChecks() {
        when(config.find()).thenReturn(Optional.of(new LedgerConfigRow("EUR", 2)));

        assertThatCode(() -> provisioner("EUR", null).provision()).doesNotThrowAnyException();
        assertThatCode(() -> provisioner("EUR", 2).provision()).doesNotThrowAnyException();
        assertThatCode(() -> provisioner(null, null).provision()).doesNotThrowAnyException();
        verify(config, never()).insertIfAbsent(anyString(), anyInt());
    }

    @Test
    @DisplayName("another currency, or other minor units, than the ledger was provisioned with refuses to start")
    void aMismatchRefusesToStart() {
        when(config.find()).thenReturn(Optional.of(new LedgerConfigRow("EUR", 2)));

        assertThatThrownBy(() -> provisioner("USD", null).provision())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ledger.currency is USD, but this ledger was provisioned in EUR");
        assertThatThrownBy(() -> provisioner("EUR", 3).provision())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ledger.minor-units is 3, but this ledger was provisioned with 2");
    }

    @Test
    @DisplayName("losing the race to another replica is checked like any later start")
    void losingTheRaceIsChecked() {
        when(config.find()).thenReturn(Optional.empty()).thenReturn(Optional.of(new LedgerConfigRow("USD", 2)));
        when(config.insertIfAbsent("EUR", 2)).thenReturn(false);

        assertThatThrownBy(() -> provisioner("EUR", null).provision())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provisioned in USD");
    }

    @Test
    @DisplayName("it runs while the context starts, so a mismatch stops the application starting")
    void runsAtStartup() {
        when(config.find()).thenReturn(Optional.of(new LedgerConfigRow("EUR", 2)));
        var context = new ApplicationContextRunner()
                .withBean(LedgerConfigRepository.class, () -> config)
                .withUserConfiguration(LedgerConfigProvisioner.class);

        // An unset LEDGER_MINOR_UNITS arrives as an empty string, and has to bind as "not configured".
        context.withPropertyValues("ledger.currency=EUR", "ledger.minor-units=")
                .run(started -> assertThat(started).hasNotFailed());
        context.withPropertyValues("ledger.currency=USD", "ledger.minor-units=")
                .run(started -> assertThat(started).getFailure()
                        .rootCause()
                        .hasMessageContaining("ledger.currency is USD, but this ledger was provisioned in EUR"));
    }

    private LedgerConfigProvisioner provisioner(@Nullable String currency, @Nullable Integer minorUnits) {
        return new LedgerConfigProvisioner(new LedgerProperties(currency, minorUnits), config);
    }
}
