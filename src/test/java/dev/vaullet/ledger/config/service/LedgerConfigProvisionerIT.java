package dev.vaullet.ledger.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vaullet.common.test.IntegrationTest;
import dev.vaullet.ledger.config.dao.LedgerConfigRepository;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Provisioning against the real {@code ledger_config}: the row it writes, and the row it refuses to
 * overwrite. Each test is a fresh "start" of the provisioner over whatever the table holds.
 */
@IntegrationTest
class LedgerConfigProvisionerIT {

    @Autowired LedgerConfigRepository config;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void emptyLedger() {
        jdbc.update("TRUNCATE ledger_config");
    }

    /** Every other test, and any context started after this class, expects the test profile's EUR. */
    @AfterEach
    void restoreTheTestCurrency() {
        jdbc.update("TRUNCATE ledger_config");
        jdbc.update("INSERT INTO ledger_config (currency, minor_units) VALUES ('EUR', 2)");
    }

    @Test
    @DisplayName("the first start writes the configured currency, its ISO minor units and the default hold ceiling")
    void provisionsAnEmptyLedger() {
        start("JPY", null);

        assertThat(row()).containsEntry("currency", "JPY")
                .containsEntry("minor_units", 0)
                .containsEntry("max_hold_seconds", 2_592_000);
    }

    @Test
    @DisplayName("a later start with the same currency changes nothing")
    void laterStartsOnlyCheck() {
        start("EUR", null);
        var first = row();

        start("EUR", 2);
        start(null, null);

        assertThat(row()).isEqualTo(first);
    }

    @Test
    @DisplayName("a start configured for another currency is refused, and the row is left as it was")
    void refusesAnotherCurrency() {
        start("EUR", null);
        var provisioned = row();

        assertThatThrownBy(() -> start("USD", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ledger.currency is USD, but this ledger was provisioned in EUR");
        assertThat(row()).isEqualTo(provisioned);
    }

    @Test
    @DisplayName("a first start without a currency is refused, and writes nothing")
    void refusesAnEmptyLedgerWithoutCurrency() {
        assertThatThrownBy(() -> start(null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ledger_config is empty and ledger.currency is not set");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_config", Integer.class)).isZero();
    }

    private void start(@Nullable String currency, @Nullable Integer minorUnits) {
        new LedgerConfigProvisioner(new LedgerProperties(currency, minorUnits), config).provision();
    }

    private Map<String, Object> row() {
        return jdbc.queryForMap("SELECT currency, minor_units, max_hold_seconds, initialized_at FROM ledger_config");
    }
}
