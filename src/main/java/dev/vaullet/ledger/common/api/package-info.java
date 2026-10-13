/**
 * Wire shapes every feature's API shares: today, the list body of ADR-011 §8. The next list —
 * an account's transactions — answers with the same one.
 *
 * <p>{@link org.jspecify.annotations.NullMarked} is declared here and not inherited from the parent
 * package: Java packages do not nest for annotation purposes.
 */
@NullMarked
package dev.vaullet.ledger.common.api;

import org.jspecify.annotations.NullMarked;
