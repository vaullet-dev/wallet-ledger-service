/**
 * Root package of the ledger service.
 *
 * <p>{@link org.jspecify.annotations.NullMarked} makes every type in this package
 * null-<em>hostile</em> by default: references are non-null unless explicitly marked
 * {@code @Nullable}. Spring Framework 7 ships JSpecify annotations throughout its own API, so IDEs
 * and NullAway/Error Prone can flag a possible {@code NullPointerException} at compile time instead
 * of at 3am.
 *
 * <p>It does NOT reach sub-packages. Java packages do not nest for annotation purposes, so every
 * package needs its own declaration — which is why {@code spring-core} ships fifty of these files.
 */
@NullMarked
package dev.vaullet.ledger;

import org.jspecify.annotations.NullMarked;
