/**
 * The wallet resource's wire shapes. Separate types from the records in {@code wallet.service} on
 * purpose: these carry Jackson, validation and OpenAPI concerns and change only with a version
 * decision (ADR-011 §4). Money is a decimal string, never a JSON number.
 *
 * <p>{@link org.jspecify.annotations.NullMarked} is declared here and not inherited from the parent
 * package: Java packages do not nest for annotation purposes.
 */
@NullMarked
package dev.vaullet.ledger.wallet.api.dto;

import org.jspecify.annotations.NullMarked;
