/**
 * The ledger's concrete throwables. The catalogue they carry — {@link
 * dev.vaullet.ledger.common.error.LedgerErrorType} — lives one package up, alongside the advice that
 * turns it into a response.
 *
 * <p>{@link org.jspecify.annotations.NullMarked} is declared here and not inherited from the parent
 * package: Java packages do not nest for annotation purposes, so a {@code @NullMarked} on
 * {@code dev.vaullet.ledger} would not reach this one. Spring Framework does the same —
 * {@code spring-core} ships fifty {@code package-info} classes, one per package.
 */
@NullMarked
package dev.vaullet.ledger.common.error.exception;

import org.jspecify.annotations.NullMarked;
