/**
 * The ledger's currency, from {@code ledger.*} into {@code ledger_config}: written on the first
 * start, checked on every start after it, and never changed (ADR-004).
 *
 * <p>{@link org.jspecify.annotations.NullMarked} is declared here and not inherited from the parent
 * package: Java packages do not nest for annotation purposes.
 */
@NullMarked
package dev.vaullet.ledger.config.service;

import org.jspecify.annotations.NullMarked;
