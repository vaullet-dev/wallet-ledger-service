/**
 * The wallet rules: opening, freezing and closing, what a balance comes to, and where each
 * transaction begins and ends. Nothing here knows about HTTP, which {@code LayeringTest} enforces.
 *
 * <p>{@link org.jspecify.annotations.NullMarked} is declared here and not inherited from the parent
 * package: Java packages do not nest for annotation purposes.
 */
@NullMarked
package dev.vaullet.ledger.wallet.service;

import org.jspecify.annotations.NullMarked;
