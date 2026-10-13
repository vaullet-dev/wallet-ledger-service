/**
 * Every statement the wallet feature issues. Nothing outside a {@code dao} package touches
 * {@code JdbcTemplate}, which {@code LayeringTest} enforces: on this codebase the statements are
 * the design (ADR-004).
 *
 * <p>{@link org.jspecify.annotations.NullMarked} is declared here and not inherited from the parent
 * package: Java packages do not nest for annotation purposes.
 */
@NullMarked
package dev.vaullet.ledger.wallet.dao;

import org.jspecify.annotations.NullMarked;
