package dev.vaullet.ledger.config.service;

import dev.vaullet.ledger.config.dao.LedgerConfigRepository;
import java.util.Currency;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Writes ADR-004's {@code ledger_config} row from {@code ledger.currency} on the first start, and
 * refuses to start against a row that says something else.
 *
 * <p>ADR-004 writes the currency once and never updates it: moving a live deployment to another
 * currency is a migration, not a configuration change. So a mismatch stops the service rather than
 * being applied, or worse, ignored while every amount is read in the wrong unit. An empty table with
 * no currency configured stops it too, because a default would be a currency nobody chose.
 *
 * <p>Runs while the context is built: after Flyway, which Boot orders before anything that uses a
 * {@code JdbcTemplate}, and before the web server takes requests, so the ledger never answers one
 * without its currency.
 */
@Component
@EnableConfigurationProperties(LedgerProperties.class)
class LedgerConfigProvisioner implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(LedgerConfigProvisioner.class);

    /** Amounts are NUMERIC(20,4); a finer minor unit could not be stored exactly. */
    static final int MAX_MINOR_UNITS = 4;

    private final LedgerProperties properties;
    private final LedgerConfigRepository config;

    LedgerConfigProvisioner(LedgerProperties properties, LedgerConfigRepository config) {
        this.properties = properties;
        this.config = config;
    }

    @Override
    public void afterPropertiesSet() {
        provision();
    }

    void provision() {
        @Nullable Currency configured = configuredCurrency();
        var stored = config.find();
        if (stored.isEmpty()) {
            if (configured == null) {
                throw new IllegalStateException("ledger_config is empty and ledger.currency is not set. Set "
                        + "LEDGER_CURRENCY to this deployment's ISO 4217 currency, e.g. EUR: the first start "
                        + "records it, and it never changes afterwards (ADR-004).");
            }
            int minorUnits = minorUnitsFor(configured);
            if (config.insertIfAbsent(configured.getCurrencyCode(), minorUnits)) {
                log.info("Provisioned ledger_config: currency {}, {} minor units",
                        configured.getCurrencyCode(), minorUnits);
            }
            // Read back rather than assumed: a replica starting at the same moment may have won.
            stored = config.find();
        }
        var row = stored.orElseThrow(() -> new IllegalStateException("ledger_config is still empty after provisioning"));

        if (configured != null && !configured.getCurrencyCode().equals(row.currency())) {
            throw new IllegalStateException(("ledger.currency is %s, but this ledger was provisioned in %s. A "
                    + "deployment's currency never changes (ADR-004): moving it to another one is a migration, "
                    + "not a configuration change.").formatted(configured.getCurrencyCode(), row.currency()));
        }
        Integer configuredMinorUnits = properties.minorUnits();
        if (configuredMinorUnits != null && configuredMinorUnits != row.minorUnits()) {
            throw new IllegalStateException(("ledger.minor-units is %d, but this ledger was provisioned with %d. "
                    + "Like the currency, it never changes (ADR-004).")
                    .formatted(configuredMinorUnits, row.minorUnits()));
        }
        log.info("Ledger currency {}, {} minor units", row.currency(), row.minorUnits());
    }

    private @Nullable Currency configuredCurrency() {
        String code = properties.currency();
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return Currency.getInstance(code.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "ledger.currency '%s' is not an ISO 4217 currency code, e.g. EUR".formatted(code), e);
        }
    }

    private int minorUnitsFor(Currency currency) {
        Integer configured = properties.minorUnits();
        // -1 for the ISO 4217 codes that have no minor unit (XAU, XXX, ...): those have to say.
        int minorUnits = configured != null ? configured : currency.getDefaultFractionDigits();
        if (minorUnits < 0 || minorUnits > MAX_MINOR_UNITS) {
            throw new IllegalStateException(("%s needs ledger.minor-units between 0 and %d (amounts are "
                    + "NUMERIC(20,4)), not %d").formatted(currency.getCurrencyCode(), MAX_MINOR_UNITS, minorUnits));
        }
        return minorUnits;
    }
}
