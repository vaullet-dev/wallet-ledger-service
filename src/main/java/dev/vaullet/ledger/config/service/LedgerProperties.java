package dev.vaullet.ledger.config.service;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The ledger's own configuration: the deployment's currency, as the operator supplies it.
 *
 * <p>ADR-004 makes the currency a deployment constant, set alongside the rest of a customer's
 * values (ADR-005) and recorded once in {@code ledger_config}. These keys are how it gets there on
 * the first start, and what every later start is checked against; see
 * {@link LedgerConfigProvisioner}.
 *
 * @param currency ISO 4217 code, e.g. {@code EUR} ({@code LEDGER_CURRENCY}). Required while
 *     {@code ledger_config} is empty.
 * @param minorUnits decimal places of the currency's minor unit ({@code LEDGER_MINOR_UNITS}). Unset
 *     means ISO 4217's: 2 for EUR, 0 for JPY, 3 for KWD.
 */
@ConfigurationProperties("ledger")
record LedgerProperties(@Nullable String currency, @Nullable Integer minorUnits) {}
